/*
 * This file is part of Adblock Plus <https://adblockplus.org/>,
 * Copyright (C) 2006-present eyeo GmbH
 *
 * Adblock Plus is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * Adblock Plus is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Adblock Plus.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.adblockplus.libadblockplus.android;

import org.adblockplus.libadblockplus.IsAllowedConnectionCallback;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.util.Log;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides single instance of AdblockEngine shared between registered clients
 */
public class SingleInstanceEngineProvider implements AdblockEngineProvider
{
  private static final String TAG = Utils.getTag(SingleInstanceEngineProvider.class);

  private Context context;
  private String basePath;
  private boolean developmentBuild;
  private AtomicReference<String> preloadedPreferenceName = new AtomicReference();
  private AtomicReference<Map<String, Integer>> urlToResourceIdMap = new AtomicReference();
  private AtomicReference<Map<String, String>> urlToFileMap = new AtomicReference();
  private AdblockEngine engine;
  private CountDownLatch engineCreated;
  private AtomicLong v8IsolateProviderPtr = new AtomicLong(0);
  private List<EngineCreatedListener> engineCreatedListeners =
    new LinkedList<EngineCreatedListener>();
  private List<EngineDisposedListener> engineDisposedListeners =
    new LinkedList<EngineDisposedListener>();
  private final Object engineLock = new Object();

  /*
    Simple ARC management for AdblockEngine
    Use `retain` and `release`
   */

  private AtomicInteger referenceCounter = new AtomicInteger(0);

  /**
   * Init with context
   * @param context application context
   * @param basePath file system root to store files
   *
   *                 Adblock Plus library will download subscription files and store them on
   *                 the path passed. The path should exist and the directory content should not be
   *                 cleared out occasionally. Using `context.getCacheDir().getAbsolutePath()` is not
   *                 recommended because it can be cleared by the system.
   * @param developmentBuild debug or release?
   */
  public SingleInstanceEngineProvider(Context context, String basePath, boolean developmentBuild)
  {
    this.context = context.getApplicationContext();
    this.basePath = basePath;
    this.developmentBuild = developmentBuild;
  }

  /**
   * Use preloaded subscriptions
   * @param preferenceName Shared Preferences name to store intercepted requests stats
   * @param urlToResourceIdMap URL to Android resource id map
   * @return this (for method chaining)
   */
  public SingleInstanceEngineProvider preloadSubscriptions(String preferenceName,
                                                           Map<String, Integer> urlToResourceIdMap)
  {
    this.preloadedPreferenceName.set(preferenceName);
    this.urlToResourceIdMap.set(urlToResourceIdMap);
    return this;
  }

  /**
   * Use preloaded subscriptions
   * @param urlToFileMap URL to Android resource id map
   * @return this (for method chaining)
   */
  public SingleInstanceEngineProvider preloadFileSubscriptions(Map<String, String> urlToFileMap)
  {
    this.urlToFileMap.set(urlToFileMap);
    return this;
  }

  public SingleInstanceEngineProvider useV8IsolateProvider(long ptr)
  {
    this.v8IsolateProviderPtr.set(ptr);
    return this;
  }

  @Override
  public SingleInstanceEngineProvider addEngineCreatedListener(EngineCreatedListener listener)
  {
    this.engineCreatedListeners.add(listener);
    return this;
  }

  @Override
  public void removeEngineCreatedListener(EngineCreatedListener listener)
  {
    this.engineCreatedListeners.remove(listener);
  }

  @Override
  public void clearEngineCreatedListeners()
  {
    this.engineCreatedListeners.clear();
  }

  @Override
  public SingleInstanceEngineProvider addEngineDisposedListener(EngineDisposedListener listener)
  {
    this.engineDisposedListeners.add(listener);
    return this;
  }

  @Override
  public void removeEngineDisposedListener(EngineDisposedListener listener)
  {
    this.engineDisposedListeners.remove(listener);
  }

  @Override
  public void clearEngineDisposedListeners()
  {
    this.engineDisposedListeners.clear();
  }

  private void createAdblock()
  {
    if(developmentBuild) Log.w(TAG, "Waiting for lock");
    synchronized (getEngineLock())
    {
      if(developmentBuild) Log.d(TAG, "Creating adblock engine ...");
      ConnectivityManager connectivityManager =
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      IsAllowedConnectionCallback isAllowedConnectionCallback =
        new IsAllowedConnectionCallbackImpl(connectivityManager);

      AdblockEngine.Builder builder = AdblockEngine
        .builder(
          AdblockEngine.generateAppInfo(context, developmentBuild),
          basePath)
        .setIsAllowedConnectionCallback(isAllowedConnectionCallback)
        .enableElementHiding(true);

      long v8IsolateProviderPtrLocal = v8IsolateProviderPtr.get();
      if (v8IsolateProviderPtrLocal != 0)
      {
        builder.useV8IsolateProvider(v8IsolateProviderPtrLocal);
      }

      Map<String, String> urlToFileMapLocal = urlToFileMap.get();
      if(urlToFileMapLocal != null) {
        builder.preloadFileSubscriptions(context, urlToFileMapLocal);
      }

      String preloadedPreferenceNameLocal = preloadedPreferenceName.get();
      Map<String, Integer> urlToResourceIdMapLocal = urlToResourceIdMap.get();
      // if preloaded subscriptions provided
      if (preloadedPreferenceNameLocal != null)
      {
        SharedPreferences preloadedSubscriptionsPrefs = context.getSharedPreferences(
          preloadedPreferenceNameLocal,
          Context.MODE_PRIVATE);
        builder.preloadSubscriptions(
          context,
          urlToResourceIdMapLocal,
          new AndroidHttpClientResourceWrapper.SharedPrefsStorage(preloadedSubscriptionsPrefs));
      }

      engine = builder.build();

      if(developmentBuild) Log.d(TAG, "AdblockHelper engine created");

      // sometimes we need to init AdblockEngine instance, eg. set user settings
      for (EngineCreatedListener listener : engineCreatedListeners)
      {
        listener.onAdblockEngineCreated(engine);
      }
    }
  }

  @Override
  public synchronized boolean retain(boolean asynchronous)
  {
    boolean firstInstance = false;

    if (referenceCounter.getAndIncrement() == 0)
    {
      firstInstance = true;

      if (!asynchronous)
      {
        createAdblock();
      }
      else
      {
        // latch is required for async (see `waitForReady()`)
        engineCreated = new CountDownLatch(1);

        new Thread(new Runnable()
        {
          @Override
          public void run()
          {
            synchronized (getEngineLock())
            {
              createAdblock();

              // unlock waiting client thread
              engineCreated.countDown();
            }
          }
        }).start();
      }
    }
    return firstInstance;
  }

  @Override
  public void waitForReady()
  {
    if (engineCreated == null)
    {
      throw new IllegalStateException("Usage exception: call retain(true) first");
    }

    try
    {
      if(developmentBuild) Log.d(TAG, "Waiting for ready in " + Thread.currentThread());
      engineCreated.await();
      if(developmentBuild) Log.d(TAG, "Ready");
    }
    catch (InterruptedException e)
    {
      if(developmentBuild) Log.w(TAG, "Interrupted", e);
    }
  }

  @Override
  public AdblockEngine getEngine()
  {
    return engine;
  }

  @Override
  public synchronized boolean release()
  {
    boolean lastInstance = false;

    if (referenceCounter.decrementAndGet() == 0)
    {
      lastInstance = true;

      if (engineCreated != null)
      {
        // retained asynchronously
        waitForReady();
        disposeAdblock();

        // to unlock waiting client in waitForReady()
        engineCreated.countDown();
        engineCreated = null;
      }
      else
      {
        disposeAdblock();
      }
    }
    return lastInstance;
  }

  private void disposeAdblock()
  {
    if(developmentBuild) Log.w(TAG, "Waiting for lock");
    synchronized (getEngineLock())
    {
      if(developmentBuild) Log.w(TAG, "Disposing adblock engine");

      engine.dispose();
      engine = null;

      // sometimes we need to deinit something after AdblockEngine instance disposed
      // eg. release user settings
      for (EngineDisposedListener listener : engineDisposedListeners)
      {
        listener.onAdblockEngineDisposed();
      }
    }
  }

  @Override
  public int getCounter()
  {
    return referenceCounter.get();
  }

  @Override
  public Object getEngineLock()
  {
    if(developmentBuild) Log.d(TAG, "getEngineLock() called from " + Thread.currentThread());
    return engineLock;
  }
}
