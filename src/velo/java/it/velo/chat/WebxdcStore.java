package it.velo.chat;

// Based on code from https://codeberg.org/upstream/velo/raw/commit/master/src/main/java/it/velo/chat/WebXDCStore.java

import static android.view.View.VISIBLE;
import static eu.siacs.conversations.ui.ActionBarActivity.configureActionBar;
import static eu.siacs.conversations.utils.AccountUtils.MANAGE_ACCOUNT_ACTIVITY;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.MenuItem;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import eu.siacs.conversations.R;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.ui.util.WebViewProxy;
import eu.siacs.conversations.services.ChannelDiscoveryService;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.databinding.ActivityWebxdcStoreBinding;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;

import android.annotation.SuppressLint;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

public class WebxdcStore extends XmppActivity {
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ActivityWebxdcStoreBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_webxdc_store);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());

        //getSupportActionBar().setDisplayShowHomeEnabled(false);
        //getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        WebView webView = findViewById(R.id.web);
        String URL = "https://webxdc.org/apps/";
        if (isNetworkAvailable(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                webView.setForceDarkAllowed(true);
            }
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    final HttpConnectionManager.Route target = routeFor(url);
                    if (target == pageRoute) {
                        view.loadUrl(url);
                    } else {
                        // Another network is needed (e.g. a .onion link): re-route first.
                        load(view, url, target);
                    }
                    return true;
                }

                // No local targets, and .onion/.i2p only through their own overlay.
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    final WebResourceResponse blocked = WebViewProxy.blockRequest(request.getUrl(), null); // webxdc.org is never local
                    return blocked != null ? blocked : super.shouldInterceptRequest(view, request);
                }
            });
            webView.setDownloadListener(new DownloadListener() {
                @Override
                public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                    final var intent = new Intent();
                    intent.setData(Uri.parse(url));
                    setResult(Activity.RESULT_OK, intent);
                    finish();
                }
            });
            // WebView ignores the app's OkHttp proxy: follow the Tor/I2P policy (or refuse).
            this.webView = webView;
            load(webView, URL, routeFor(URL));
        } else {
            Toast.makeText(this, R.string.account_status_no_internet, Toast.LENGTH_LONG).show();
        }
    }

    // Route the current page was loaded with (navigation to another route re-routes first).
    private HttpConnectionManager.Route pageRoute = HttpConnectionManager.Route.DIRECT;
    private WebView webView;

    @Override
    protected void onDestroy() {
        // Release the page so its route no longer pins the process-wide WebView proxy.
        if (webView != null) WebViewProxy.release(webView);
        super.onDestroy();
    }

    private HttpConnectionManager.Route routeFor(final String url) {
        final var preferences = PreferenceManager.getDefaultSharedPreferences(this);
        return HttpConnectionManager.route(
                Uri.parse(url).getHost(),
                preferences.getBoolean("use_tor", getResources().getBoolean(R.bool.use_tor)),
                preferences.getBoolean("use_i2p", getResources().getBoolean(R.bool.use_i2p)));
    }

    private void load(final WebView webView, final String url, final HttpConnectionManager.Route route) {
        WebViewProxy.apply(this, webView, route, () -> {
            if (isFinishing() || isDestroyed()) return;
            pageRoute = route;
            webView.loadUrl(url);
        }, (message) -> {
            if (isFinishing() || isDestroyed()) return;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    @Override
    protected void refreshUiReal() {

    }

    @Override
    protected void onBackendConnected() {
    }

    //check for internet connection
    private boolean isNetworkAvailable(Context context) {
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null) {
            NetworkInfo[] info = connectivity.getAllNetworkInfo();
            if (info != null) {
                for (NetworkInfo anInfo : info) {
                    if (anInfo.getState() == NetworkInfo.State.CONNECTED) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
