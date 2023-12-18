/*
 * Copyright (C) 2019 Charter Communications
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.spectrum.android.ping;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.ViewFlipper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class MainActivity extends Activity {
    private TextView mLog;
    private PingRunnable mPingRunnable;

    private int mPingMethod;

    public static Network getNetwork(final Context context, final int transport) {
        final ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        for (Network network : connManager.getAllNetworks()) {
            NetworkCapabilities networkCapabilities = connManager.getNetworkCapabilities(network);
            if (networkCapabilities != null &&
                    networkCapabilities.hasTransport(transport) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return network;
            }
        }
        return null;
    }



    private boolean executeCommand(EditText address) {
        Runtime runtime = Runtime.getRuntime();
        try {
            Process ipProcess = runtime.exec("/system/bin/ping -c 1 -t 5 -W 3 " + address.getText());
            BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(ipProcess.getInputStream()));
            BufferedReader stdError = new BufferedReader(new
                    InputStreamReader(ipProcess.getErrorStream()));

            String rsMsg = "";
            String s = null;
            while ((s = stdInput.readLine()) != null) {
                rsMsg = rsMsg + s + "\n";
                System.out.println(s);
            }
            while ((s = stdError.readLine()) != null) {
                rsMsg = rsMsg + "ERROR: " + s + "\n";
            }
            TextView pingRs = findViewById(R.id.runtimeRs);
            pingRs.setText(rsMsg);
            int exitValue = ipProcess.waitFor();
            ipProcess.destroy();
            return (exitValue == 0);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mLog = findViewById(R.id.extRs);
        final EditText address = findViewById(R.id.address);
        final CheckBox wifi = findViewById(R.id.wifi);
        final RadioGroup ipRadioGroup = findViewById(R.id.ipGroup);

        final ViewFlipper vf = findViewById(R.id.vf);
        final RadioGroup methodGroup = findViewById(R.id.methodGroup);
        final EditText count = findViewById(R.id.countOfPing);


        mPingMethod = methodGroup.getCheckedRadioButtonId();

        methodGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup radioGroup, int i) {
                if (i == R.id.externalLib) {
                    vf.setDisplayedChild(0);
                } else if (i == R.id.runtimeJava) {
                    vf.setDisplayedChild(1);
                }
                mPingMethod = i;
            }
        });


        findViewById(R.id.ping).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View view) {
                if (mPingMethod == R.id.externalLib) {
                    emitExtLib(ipRadioGroup, address, wifi);
                } else {
                    String c = count.getText().toString();
                    executeCommand(address);
                }
            }
        });

    }

    private void pingCommand(int count, EditText address) {
        final TextView pingRs = findViewById(R.id.runtimeRs);
        long sum = 0;
        for (int i = 0; i < count; i++) {
            long start = (System.currentTimeMillis() % 100000);
            executeCommand(address);
            long end = (System.currentTimeMillis() % 100000);

            if (end <= start) {
                sum = sum + ((100000 - start) + end);
            } else {
                sum = sum + (end - start);
            }
        }
        Long l = new Long(sum);
        pingRs.setText("Average Time for Ping is " + (l.doubleValue() / count) + " ms");
    }

    public void emitExtLib(RadioGroup ipRadioGroup, EditText address, CheckBox wifi) {
        if (mPingRunnable != null) {
            mPingRunnable.cancel();
        }

        final Class<? extends InetAddress> inetClass;
        final int radioId = ipRadioGroup.getCheckedRadioButtonId();
        if (radioId == R.id.ipv6) {
            inetClass = Inet6Address.class;
        } else if (radioId == R.id.ipv4) {
            inetClass = Inet4Address.class;
        } else {
            inetClass = InetAddress.class;
        }
        AsyncTask.SERIAL_EXECUTOR.execute(mPingRunnable =
                new PingRunnable(address.getText().toString(), wifi.isChecked(), inetClass));
    }

    /**
     * Attempts to get an IPv6 address
     * @param host
     * @return
     * @throws UnknownHostException
     */

    static InetAddress getInetAddress(final String host, Class<? extends InetAddress> inetClass) throws UnknownHostException {
        final InetAddress[] inetAddresses = InetAddress.getAllByName(host);
        InetAddress dest = null;
        for (final InetAddress inetAddress : inetAddresses) {
            if (inetClass.equals(inetAddress.getClass())) {
                return inetAddress;
            }
        }
        throw new UnknownHostException("Could not find IP address of type " + inetClass.getSimpleName());
    }

    class PingRunnable implements Runnable {
        final private StringBuilder mSb = new StringBuilder();
        final private String mHost;
        final private boolean mWifi;
        final private Class<? extends InetAddress> mInetClass;

        private Ping mPing;

        final private Runnable textSetter = new Runnable() {
            @Override
            public void run() {
                mLog.setText(mSb.toString());
            }
        };

        public PingRunnable(final String host, final boolean wifi, final Class<? extends InetAddress> inetClass) {
            mHost = host;
            mWifi = wifi;
            mInetClass = inetClass;
        }

        public void run() {
            try {
                final InetAddress dest;
                if (mInetClass == InetAddress.class) {
                    dest = InetAddress.getByName(mHost);
                } else {
                    dest = getInetAddress(mHost, mInetClass);
                }
                Ping ping = new Ping(dest, new Ping.PingListener() {
                    @Override
                    public void onPing(final long timeMs, final int count) {
                        appendMessage("#" + count + " ms: " + timeMs + " ip: " + dest.getHostAddress(), null);
                    }

                    @Override
                    public void onPingException(final Exception e, final int count) {
                        appendMessage("#" + count  + " ip: " + dest.getHostAddress(), e);
                    }

                });

                if (mWifi) {
                    final Network network = getNetwork(getApplicationContext(), NetworkCapabilities.TRANSPORT_WIFI);
                    if (network == null) {
                        throw new UnknownHostException("Failed to find a WiFi Network");
                    }
                    ping.setNetwork(network);
                }
                mPing = ping;
                ping.run();
            } catch(UnknownHostException e) {

                appendMessage("Unknown host", e);
            }
        }

        public void cancel() {
            if (mPing != null) {
                mPing.setCount(0);
            }
        }

        private void appendMessage(final String message, final Exception e) {
            Log.d("Ping", message, e);
            mSb.append(message);
            if (e != null) {
                mSb.append(" Error: ");
                mSb.append(e.getMessage());
            }
            mSb.append('\n');
            runOnUiThread(textSetter);
        }
    }
}
