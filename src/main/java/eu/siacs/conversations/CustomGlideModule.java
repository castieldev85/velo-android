package eu.siacs.conversations;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;

import eu.siacs.conversations.http.HttpConnectionManager;
import okhttp3.OkHttpClient;

@GlideModule
public class CustomGlideModule extends AppGlideModule {

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {

        // Start with the app's base OkHttpClient, which is already configured with the app's custom trust managers.
        // This is the crucial step that makes direct connections (without a proxy) work correctly.
        final OkHttpClient baseClient = HttpConnectionManager.okHttpClient(context);

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Host-aware, per-request selector: .onion/.i2p images always go through their overlay,
        // everything else follows the current Tor/I2P preference (fail-closed, never direct).
        final OkHttpClient clientWithProxy = HttpConnectionManager.applyProxyPolicy(
                        baseClient.newBuilder(),
                        () -> preferences.getBoolean("use_tor", context.getResources().getBoolean(R.bool.use_tor)),
                        () -> preferences.getBoolean("use_i2p", context.getResources().getBoolean(R.bool.use_i2p)))
                .build();

        final OkHttpUrlLoader.Factory factory = new OkHttpUrlLoader.Factory(clientWithProxy);

        registry.replace(GlideUrl.class, InputStream.class, factory);
    }
}
