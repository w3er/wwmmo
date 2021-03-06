package au.com.codeka.warworlds.model;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import javax.annotation.Nullable;

import android.os.Handler;
import android.util.SparseArray;

import au.com.codeka.common.Log;
import au.com.codeka.common.protobuf.Messages;
import au.com.codeka.warworlds.api.ApiRequest;
import au.com.codeka.warworlds.api.RequestManager;
import au.com.codeka.warworlds.eventbus.EventBus;

/**
 * Manages fetching and caching of the stars "owned" by your empire.
 * <p/>
 * Stars that the empire owns are conceptually kept in a sorted array, ordered alphabetically by
 * the star's name. You can call {@link #getStars} to get the stars we currently have cached in
 * a given range. If needed, this will make a request to the server to fetch new stars, which
 * will then be cached.
 */
public class EmpireStarsFetcher {
  private static final Log log = new Log("EmpireStarManager");

  public final EventBus eventBus = new EventBus();

  /**
   * Keep a WeakReference to all the stars, this class only exists for as long as you're on an
   * activity that displays all your stars (i.e. not forever) so this doesn't need to be LRU or
   * whatever.
   */
  private final SparseArray<WeakReference<Star>> cache = new SparseArray<>();

  private final Handler handler = new Handler();
  private final Object indicesToFetchLock = new Object();
  private ArrayList<Integer> indicesToFetch;
  private Filter filter;
  private String search;
  private int numStars;

  public EmpireStarsFetcher(Filter filter, String search) {
    this.filter = filter;
    this.search = search;
  }

  /** Gets the number of stars we own. */
  public int getNumStars() {
    return numStars;
  }

  /**
   * Determines whether the given star is currently cached or not. Note that because the stars are
   * kept in a {@link WeakReference}, this could be out-of-date as soon as it's called.
   */
  public boolean hasStarID(int starID) {
    synchronized (cache) {
      for (int i = 0; i < cache.size(); i++) {
        WeakReference<Star> starRef = cache.valueAt(i);
        Star star = starRef.get();
        if (star != null && star.getID() == starID) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Gets the star at the given index. If we don't have the star cached, we'll wait a couple of
   * milliseconds before fetching all the stars we've been asked for.
   */
  public Star getStar(int index) {
    WeakReference<Star> ref = cache.get(index);
    Star star = (ref != null ? ref.get() : null);
    if (star == null) {
      synchronized (indicesToFetchLock) {
        if (indicesToFetch == null) {
          indicesToFetch = new ArrayList<>();
          handler.postDelayed(starFetchRunnable, 150);
        }
        indicesToFetch.add(index);
      }
    }

    return star;
  }

  /**
   * You can call this if you get notified about a star update, we'll update our copy as well.
   */
  public boolean onStarUpdated(Star star) {
    synchronized (cache) {
      for (int i = 0; i < cache.size(); i++) {
        WeakReference<Star> ref = cache.valueAt(i);
        Star thisStar = ref.get();
        if (thisStar != null && thisStar.getID() == star.getID()) {
          cache.setValueAt(i, new WeakReference<>(star));
          return true;
        }
      }
    }

    return false;
  }

  private Runnable starFetchRunnable = new Runnable() {
    @Override
    public void run() {
      ArrayList<Integer> indices;
      synchronized (indicesToFetchLock) {
        if (indicesToFetch == null) {
          return;
        }

        indices = new ArrayList<>(indicesToFetch);
        indicesToFetch = null;
      }
      Collections.sort(indices);
      fetchStars(indices);
    }
  };

  /**
   * Attempts to fetch the stars in the empire's list of stars between the given start and end
   * index (inclusive). Stars may be null if we haven't refreshed them from the server yet, in
   * which case you should subscribe to the {@link StarsFetchedEvent} to be notified when it has
   * been refreshed from the server.
   */
  public SparseArray<Star> getStars(int startIndex, int endIndex) {
    ArrayList<Integer> missing = null;
    SparseArray<Star> stars = new SparseArray<>();
    for (int i = startIndex; i <= endIndex; i++) {
      WeakReference<Star> ref = cache.get(i);
      Star star = (ref != null ? ref.get() : null);
      if (star == null) {
        if (missing == null) {
          missing = new ArrayList<>();
        }
        missing.add(i);
      } else {
        stars.put(i, star);
      }
    }

    if (missing != null) {
      fetchStars(missing);
    }

    return stars;
  }

  public void indexOf(int starID, final IndexOfCompleteHandler onCompleteHandler) {
    final StringBuilder url = new StringBuilder();
    url.append("empires/");
    url.append(Integer.toString(EmpireManager.i.getEmpire().getID()));
    url.append("/stars?indexof=");
    url.append(starID);
    appendFilterAndSearch(url);
    log.debug("Fetching: %s", url);

    RequestManager.i.sendRequest(new ApiRequest.Builder(url.toString(), "GET")
        .completeCallback(new ApiRequest.CompleteCallback() {
          @Override
          public void onRequestComplete(ApiRequest request) {
            int index = Integer.parseInt(request.bodyString());
            if (index < 0) {
              onCompleteHandler.onIndexOfComplete(null);
            } else {
              onCompleteHandler.onIndexOfComplete(index);
            }
          }
        }).build());
  }

  /**
   * Sends a request to the server to fetch the given stars. We assume the collection is
   * sorted.
   */
  private void fetchStars(Collection<Integer> indices) {
    final StringBuilder url = new StringBuilder();
    url.append("empires/");
    url.append(Integer.toString(EmpireManager.i.getEmpire().getID()));
    url.append("/stars?indices=");
    int lastIndex = -1;
    for (Integer index : indices) {
      if (lastIndex < 0 || lastIndex < (index - 1)) {
        if (lastIndex < 0) {
          url.append(Integer.toString(index));
          url.append("-");
        } else {
          url.append(Integer.toString(lastIndex));
          url.append(",");
          url.append(Integer.toString(index));
          url.append("-");
        }
      }
      lastIndex = index;
    }
    lastIndex += 5; // fetch 5 more stars than we actually need
    if (lastIndex >= numStars) {
      lastIndex = numStars + 4; // try to fetch a bit more
    }
    url.append(Integer.toString(lastIndex));
    appendFilterAndSearch(url);
    log.debug("Fetching: %s", url);

    RequestManager.i.sendRequest(new ApiRequest.Builder(url.toString(), "GET")
        .completeCallback(fetchCompleteCallback)
        .build());
  }

  private final ApiRequest.CompleteCallback fetchCompleteCallback
      = new ApiRequest.CompleteCallback() {
    @Override
    public void onRequestComplete(ApiRequest request) {
      Messages.EmpireStars pb = request.body(Messages.EmpireStars.class);
      SparseArray<Star> stars = new SparseArray<>();
      for (Messages.EmpireStar empire_star_pb : pb.getStarsList()) {
        Star star = new Star();
        star.fromProtocolBuffer(empire_star_pb.getStar());
        stars.put(empire_star_pb.getIndex(), star);
      }

      numStars = pb.getTotalStars();
      synchronized (cache) {
        for (int i = 0; i < stars.size(); i++) {
          Star star = stars.valueAt(i);
          // notify the StarManager as well, in case someone else is interested in this star.
          StarManager.i.notifyStarUpdated(star);
          cache.put(stars.keyAt(i), new WeakReference<>(star));
        }
      }

      eventBus.publish(new StarsFetchedEvent(stars));
    }
  };

  private void appendFilterAndSearch(StringBuilder url) {
    url.append("&filter=");
    url.append(filter.toString().toLowerCase());
    if (search != null) {
      url.append("&search=");
      try {
        url.append(URLEncoder.encode(search, "utf-8"));
      } catch (UnsupportedEncodingException e) {
        // Shouldn't happen.
      }
    }
  }

  public static class StarsFetchedEvent {
    public SparseArray<Star> stars;

    public StarsFetchedEvent(SparseArray<Star> stars) {
      this.stars = stars;
    }
  }

  public interface IndexOfCompleteHandler {
    public void onIndexOfComplete(@Nullable Integer index);
  }

  public enum Filter {
    Everything,
    Colonies,
    Fleets,
    Building,
    NotBuilding
  }
}
