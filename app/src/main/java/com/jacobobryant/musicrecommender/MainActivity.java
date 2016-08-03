package com.jacobobryant.musicrecommender;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.Html;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.SimpleExpandableListAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.spotify.sdk.android.authentication.AuthenticationClient;
import com.spotify.sdk.android.authentication.AuthenticationRequest;
import com.spotify.sdk.android.authentication.AuthenticationResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class MainActivity extends AppCompatActivity {
    public static final String TAG = "PlaylistUploader";
    public static final String AUTHORITY = "com.jacobobryant.musicrecommender";
    public static final String ACCOUNT_TYPE = "com.jacobobryant";
    public static final String ACCOUNT = "mycoolaccount";
    public static final String IP = "192.34.57.201";
    private static final String SPOTIFY_CLIENT_ID = "ce0589fdfe4a4c978dd89f24b0a4b4bd";
    private static final String REDIRECT_URI = "musicrecommender://spotifycallback";
    private static final int REQUEST_CODE = 666;
    Account mAccount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (consentGiven()) {
            mAccount = CreateSyncAccount(this);
            show();
        } else {
            getConsent();
        }
    }

    public static Account CreateSyncAccount(Context context) {
        final long SYNC_INTERVAL = 60L * 60L * 24L * 7L;
        Account newAccount = new Account(ACCOUNT, ACCOUNT_TYPE);
        AccountManager accountManager = (AccountManager) context.getSystemService(ACCOUNT_SERVICE);
        if (accountManager.addAccountExplicitly(newAccount, null, null)) {
            Log.d(TAG, "creating new account");
            ContentResolver.setIsSyncable(newAccount, AUTHORITY, 1);
            ContentResolver.setSyncAutomatically(newAccount, AUTHORITY, true);
        }
        ContentResolver.addPeriodicSync(newAccount, AUTHORITY, Bundle.EMPTY, SYNC_INTERVAL);
        Log.d(TAG, "returning account");
        return newAccount;
    }

    public void sync() {
        Bundle settingsBundle = new Bundle();
        settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        Log.d(TAG, "calling requestSync()");
        ContentResolver.requestSync(mAccount, AUTHORITY, settingsBundle);
        Toast.makeText(this, "Getting recommendations from server...", Toast.LENGTH_LONG).show();
    }

    public void show() {
        ExpandableListView list = (ExpandableListView) findViewById(R.id.lstRecommendations);

        ExpandableListAdapter adapter = makeAdapter();
        list.setAdapter(adapter);
        for (int position = 0; position < adapter.getGroupCount(); position++) {
            list.expandGroup(position);
        }

        if (adapter.getGroupCount() == 0) {
            findViewById(android.R.id.empty).setVisibility(View.VISIBLE);
        } else {
            findViewById(android.R.id.empty).setVisibility(View.INVISIBLE);
        }
    }

    private ExpandableListAdapter makeAdapter() {
        // load recommendations
        SQLiteDatabase db = new Database(this).getReadableDatabase();
        Cursor result = db.rawQuery("SELECT playlist_name, title, artist, album, score " +
                                    "FROM recommendations ORDER BY playlist_name", new String[]{});
        Map<String, List<Recommendation>> recommendations = new TreeMap<>();
        result.moveToPosition(-1);
        while (result.moveToNext()) {
            Recommendation rec = new Recommendation();
            String playlistName = result.getString(0);
            rec.title = result.getString(1);
            rec.artist = result.getString(2);
            rec.album = result.getString(3);
            rec.score = result.getDouble(4);

            if (!recommendations.containsKey(playlistName)) {
                recommendations.put(playlistName, new LinkedList<Recommendation>());
            }
            recommendations.get(playlistName).add(rec);
        }

        // put recommendations in adapter
        final String NAME = "NAME";
        List<Map<String, String>> headers = new ArrayList<>();
        List<List<Map<String, String>>> childData = new ArrayList<>();

        for (Map.Entry<String, List<Recommendation>> entry : recommendations.entrySet()) {
            // set header
            Map<String, String> headerItem = new HashMap<>();
            String playlistName = entry.getKey();
            headerItem.put(NAME, playlistName);
            headers.add(headerItem);

            // set recommendations
            List<Recommendation> recList = entry.getValue();
            List<Map<String, String>> children = new ArrayList<>();
            for (Recommendation rec : recList) {
                // Handle empty playlist
                if (rec.score < 0) {
                    Map<String, String> childItem = new HashMap<>();
                    childItem.put(NAME, "No recommendations available yet.");
                    children.add(childItem);
                    break;
                }

                // Handle nonempty playlist
                int percent = (int) (100 * (rec.score));
                String text = rec.title + "\n" + rec.artist + " (" + percent + "%)";

                Map<String, String> childItem = new HashMap<>();
                childItem.put(NAME, text);
                children.add(childItem);
            }
            childData.add(children);
        }

        return new SimpleExpandableListAdapter(this,
                headers, R.layout.list_parent,
                new String[] { NAME }, new int[] { android.R.id.text1 },
                childData, android.R.layout.simple_expandable_list_item_2,
                new String[] { NAME }, new int[] { android.R.id.text1 });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_refresh:
                Log.d(TAG, "refresh()");
                if (consentGiven()) {
                    sync();
                } else {
                    getConsent();
                }
                return true;
            case R.id.action_info:
                showInfoDialog();
                return true;
            case R.id.action_spotify:
                spotifyLogin();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.action_bar, menu);
        return true;
    }

    private BroadcastReceiver syncFinishedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Toast.makeText(context, "Recommendations updated", Toast.LENGTH_SHORT).show();
            show();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(syncFinishedReceiver,
                new IntentFilter("com.jacobobryant.musicrecommender.SYNC_FINISHED"));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(syncFinishedReceiver);
    }

    void getConsent() {
        Spanned msg = Html.fromHtml(
                getResources().getString(R.string.consent_dialog_msg));

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Consent Agreement")
               .setMessage(msg)
               .setPositiveButton("Accept", new DialogInterface.OnClickListener() {
                   public void onClick(DialogInterface dialog, int id) {
                       setConsentGiven();
                   }
               })
               .setNegativeButton("Decline", new DialogInterface.OnClickListener() {
                   public void onClick(DialogInterface dialog, int id) {
                       finish();
                   }
               });

        AlertDialog alertDialog = builder.create();
        alertDialog.show();
        TextView msgTxt = (TextView) alertDialog.findViewById(android.R.id.message);
        msgTxt.setMovementMethod(LinkMovementMethod.getInstance());
    }

    boolean consentGiven() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        return prefs.getBoolean("consent_given", false);
    }

    void setConsentGiven() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putBoolean("consent_given", true).commit();
        mAccount = CreateSyncAccount(this);
        sync();
    }

    void showInfoDialog() {
        Spanned msg = Html.fromHtml(
                getResources().getString(R.string.info_dialog_msg));

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("How it works")
               .setMessage(msg)
               .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                   public void onClick(DialogInterface dialog, int id) { }
               });

        AlertDialog alertDialog = builder.create();
        alertDialog.show();
        TextView msgTxt = (TextView) alertDialog.findViewById(android.R.id.message);
        msgTxt.setMovementMethod(LinkMovementMethod.getInstance());
    }


    void spotifyLogin() {
        Log.d(TAG, "spotifyLogin()");
        AuthenticationRequest.Builder builder = new AuthenticationRequest.Builder(
                SPOTIFY_CLIENT_ID, AuthenticationResponse.Type.TOKEN, REDIRECT_URI);
        builder.setScopes(new String[]{"user-read-private"});
        AuthenticationRequest request = builder.build();
        AuthenticationClient.openLoginActivity(this, REQUEST_CODE, request);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        Log.d(TAG, "onActivityResult()");
        if (requestCode == REQUEST_CODE) {
            Log.d(TAG, "correct request code");
            AuthenticationResponse response = AuthenticationClient.getResponse(resultCode, intent);
            Log.d(TAG, response.getType().toString());
            if (response.getType() == AuthenticationResponse.Type.TOKEN) {
                Log.d(TAG, "access token: " + response.getAccessToken());

                SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor editor = settings.edit();
                editor.putString("spotify_token", response.getAccessToken());
                editor.commit();
            }
        }
    }
}
