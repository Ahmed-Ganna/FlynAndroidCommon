package com.flyn.util.netstate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.flyn.util.netstate.NetWorkUtil.netType;

import java.util.ArrayList;

/**
 * @Title NetworkStateReceiver
 * @Description 是一个检测网络状态改变的，需要配置 <receiver
 * android:name="com.flyn.util.netstate.NetworkStateReceiver" >
 * <intent-filter> <action
 * android:name="android.net.conn.CONNECTIVITY_CHANGE" /> <action
 * android:name="android.gzcpc.conn.CONNECTIVITY_CHANGE" />
 * </intent-filter> </receiver>
 * <p/>
 * 需要开启权限 <uses-permission
 * android:name="android.permission.CHANGE_NETWORK_STATE" />
 * <uses-permission
 * android:name="android.permission.CHANGE_WIFI_STATE" />
 * <uses-permission
 * android:name="android.permission.ACCESS_NETWORK_STATE" />
 * <uses-permission
 * android:name="android.permission.ACCESS_WIFI_STATE" />
 */
public class NetworkStateReceiver extends BroadcastReceiver
{
    public final static String ANDROID_NET_CHANGE_ACTION = "android.net.conn.CONNECTIVITY_CHANGE";
    public final static String FLYN_ANDROID_NET_CHANGE_ACTION = "flyn.android.net.conn.CONNECTIVITY_CHANGE";
    private static final String TAG = "NetworkStateReceiver";
    private static Boolean networkAvailable = false;
    private static netType netType;
    private static ArrayList<NetChangeObserver> netChangeObserverArrayList = new ArrayList<NetChangeObserver>();
    private static BroadcastReceiver receiver;
    private static IntentFilter filter;

    public static BroadcastReceiver getReceiver()
    {
        if (receiver == null)
        {
            receiver = new NetworkStateReceiver();
        }
        return receiver;
    }

    public static IntentFilter getIntentFilter()
    {
        if (filter == null)
        {
            filter = new IntentFilter();
            filter.addAction(NetworkStateReceiver.ANDROID_NET_CHANGE_ACTION);
            filter.addAction(NetworkStateReceiver.FLYN_ANDROID_NET_CHANGE_ACTION);
        }
        return filter;
    }

    /**
     * 注册网络状态广播
     *
     * @param mContext
     */
    public static void registerNetworkStateReceiver(Context mContext)
    {
        IntentFilter filter = new IntentFilter();
        filter.addAction(FLYN_ANDROID_NET_CHANGE_ACTION);
        filter.addAction(ANDROID_NET_CHANGE_ACTION);
        mContext.getApplicationContext().registerReceiver(getReceiver(), filter);
    }

    /**
     * 检查网络状态
     *
     * @param mContext
     */
    public static void checkNetworkState(Context mContext)
    {
        Intent intent = new Intent();
        intent.setAction(FLYN_ANDROID_NET_CHANGE_ACTION);
        mContext.sendBroadcast(intent);
    }

    /**
     * 注销网络状态广播
     *
     * @param mContext
     */
    public static void unRegisterNetworkStateReceiver(Context mContext)
    {
        if (receiver != null)
        {
            try
            {
                mContext.getApplicationContext().unregisterReceiver(receiver);
            } catch (Exception e)
            {
                Log.d(TAG, e.getMessage());
            }
        }

    }

    /**
     * 获取当前网络状态，true为网络连接成功，否则网络连接失败
     *
     * @return
     */
    public static Boolean isNetworkAvailable()
    {
        return networkAvailable;
    }

    public static netType getAPNType()
    {
        return netType;
    }

    /**
     * 注册网络连接观察者
     *
     * @param observerKey observerKey
     */
    public static void registerObserver(NetChangeObserver observer)
    {
        if (netChangeObserverArrayList == null)
        {
            netChangeObserverArrayList = new ArrayList<NetChangeObserver>();
        }
        netChangeObserverArrayList.add(observer);
    }

    /**
     * 注销网络连接观察者
     *
     * @param resID observerKey
     */
    public static void removeRegisterObserver(NetChangeObserver observer)
    {
        if (netChangeObserverArrayList != null)
        {
            netChangeObserverArrayList.remove(observer);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent)
    {
        receiver = NetworkStateReceiver.this;
        if (intent.getAction().equalsIgnoreCase(ANDROID_NET_CHANGE_ACTION) || intent.getAction().equalsIgnoreCase(FLYN_ANDROID_NET_CHANGE_ACTION))
        {
            Log.i(TAG, "网络状态改变.");
            if (!NetWorkUtil.isNetworkAvailable(context))
            {
                Log.i(TAG, "没有网络连接.");
                networkAvailable = false;

            } else
            {
                Log.i(TAG, "网络连接成功.");
                netType = NetWorkUtil.getAPNType(context);
                networkAvailable = true;
            }
            notifyObserver();
        }
    }

    private void notifyObserver()
    {

        for (int i = 0; i < netChangeObserverArrayList.size(); i++)
        {
            NetChangeObserver observer = netChangeObserverArrayList.get(i);
            if (observer != null)
            {
                if (isNetworkAvailable())
                {
                    observer.onConnect(netType);
                } else
                {
                    observer.onDisConnect();
                }
            }
        }

    }

}