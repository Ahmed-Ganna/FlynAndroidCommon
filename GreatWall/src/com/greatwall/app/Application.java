package com.greatwall.app;

import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import android.os.Handler;

import com.greatwall.app.manager.AppManager;

public class Application extends android.app.Application
{

    // singleton
    private static Application    mContext;
    private volatile boolean      mIsRunning      = false;
    private ArrayList<AppManager> mAppManagerList = new ArrayList<AppManager>();
    private Handler               backgroundHandler;
    private Executor              backgroundExecutor;

    @Override
    public void onCreate()
    {
        super.onCreate();
        mContext = this;
        init();
    }

    private void init()
    {
        this.backgroundHandler = new Handler();
        this.backgroundExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
        {
            @Override
            public Thread newThread(Runnable runnable)
            {
                Thread thread = new Thread(runnable, "Background executor service");
                thread.setPriority(Thread.MIN_PRIORITY);
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public static Application getInstance()
    {
        if (mContext == null)
            throw new IllegalStateException();
        return mContext;
    }

    public void addManager(AppManager appManager)
    {
        this.mAppManagerList.add(appManager);
    }

    public void clear()
    {
        for (int i = 0, len = mAppManagerList.size(); i < len; i++)
        {
            mAppManagerList.get(i).onClear();
        }
    }

    public void close()
    {
        mIsRunning = false;
        for (int i = 0, len = mAppManagerList.size(); i < len; i++)
        {
            mAppManagerList.get(i).onClose();
        }
    }

    public Handler getHandler()
    {
        return backgroundHandler;
    }

    public void runOnUiThread(final Runnable runnable)
    {
        backgroundHandler.post(runnable);
    }

    public void runOnUiThreadDelay(final Runnable runnable, long delayMillis)
    {
        backgroundHandler.postDelayed(runnable, delayMillis);
    }
}
