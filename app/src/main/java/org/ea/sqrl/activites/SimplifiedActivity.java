package org.ea.sqrl.activites;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.CountDownTimer;
import android.preference.PreferenceManager;
import android.support.design.widget.Snackbar;
import android.text.SpannableString;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.google.android.flexbox.FlexboxLayout;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.ea.sqrl.R;
import org.ea.sqrl.activites.base.LoginBaseActivity;
import org.ea.sqrl.processors.BioAuthenticationCallback;
import org.ea.sqrl.processors.CommunicationFlowHandler;
import org.ea.sqrl.processors.CommunicationHandler;
import org.ea.sqrl.processors.SQRLStorage;
import org.ea.sqrl.utils.Utils;

import java.security.KeyStore;
import java.util.regex.Matcher;

import javax.crypto.Cipher;

/**
 *
 * @author Daniel Persson
 */
public class SimplifiedActivity extends LoginBaseActivity {
    private static final String TAG = "SimplifiedActivity";

    private CountDownTimer quickPassCountdownTimer = null;
    private TextView txtSelectedIdentityHeadline = null;
    private TextView txtSelectedIdentity = null;
    private FlexboxLayout quickPassLayout = null;
    private TextView txtQuickPassTimeout = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simplified);

        rootView = findViewById(R.id.simplifiedActivityView);
        communicationFlowHandler = CommunicationFlowHandler.getInstance(this, handler);

        txtSelectedIdentityHeadline = findViewById(R.id.txtSelectedIdentityHeadline);
        txtSelectedIdentityHeadline.append(":");

        txtSelectedIdentity = findViewById(R.id.txtSelectedIdentity);
        txtSelectedIdentity.setOnClickListener(
                v -> {
                    startActivity(new Intent(this, MainActivity.class));
                }
        );

        quickPassLayout = findViewById(R.id.quickPassLayout);
        txtQuickPassTimeout = findViewById(R.id.txtQuickPassTimeout);

        findViewById(R.id.btnClearQuickPass).setOnClickListener(
                v -> {
                    SQRLStorage storage = SQRLStorage.getInstance(SimplifiedActivity.this.getApplicationContext());
                    storage.clearQuickPass();

                    if (quickPassCountdownTimer != null) {
                        quickPassCountdownTimer.cancel();
                        checkQuickPassStatus();
                    }
                }
        );
        findViewById(R.id.btnExtendQuickPassTimeout).setOnClickListener(
                v -> {
                    clearQuickPassDelayed();
                }
        );

        TextView txtQuickPassActiveHeadline = findViewById(R.id.txtQuickPassActiveHeadline);
        txtQuickPassActiveHeadline.append(":");

        final IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE_TYPES);
        integrator.setCameraId(0);
        integrator.setBeepEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.setBarcodeImageEnabled(false);

        setupLoginPopupWindow(getLayoutInflater());
        setupErrorPopupWindow(getLayoutInflater());
        setupBasePopups(getLayoutInflater(), false);

        final ImageButton btnUseIdentity = findViewById(R.id.btnUseIdentity);
        btnUseIdentity.setOnClickListener(
            v -> {
                integrator.setPrompt(this.getString(R.string.scan_site_code));
                integrator.initiateScan();
            }
        );
    }

    @Override
    protected void onResume() {
        super.onResume();

        boolean runningTest = getIntent().getBooleanExtra("RUNNING_TEST", false);
        if(runningTest) return;

        if(!mDbHelper.hasIdentities()) {
            startActivity(new Intent(this, StartActivity.class));
        } else {
            SharedPreferences sharedPref = this.getApplication().getSharedPreferences(
                    APPS_PREFERENCES,
                    Context.MODE_PRIVATE
            );
            long currentId = sharedPref.getLong(CURRENT_ID, 0);
            if(currentId != 0) {
                byte[] identityData = mDbHelper.getIdentityData(currentId);
                SQRLStorage storage = SQRLStorage.getInstance(SimplifiedActivity.this.getApplicationContext());
                try {
                    storage.read(identityData);

                    if (mDbHelper.getIdentities().size() > 1) {
                        String identityName = mDbHelper.getIdentityName(currentId);
                        if (identityName.length() > 20) {
                            identityName = identityName.substring(0, 20) + "...";
                        }
                        SpannableString ssIdentityName = new SpannableString(identityName);
                        ssIdentityName.setSpan(new UnderlineSpan(), 0, ssIdentityName.length(), 0);
                        txtSelectedIdentity.setText(ssIdentityName);
                        txtSelectedIdentity.setVisibility(View.VISIBLE);
                        txtSelectedIdentityHeadline.setVisibility(View.VISIBLE);
                    } else {
                        txtSelectedIdentityHeadline.setVisibility(View.GONE);
                        txtSelectedIdentity.setVisibility(View.GONE);
                    }

                } catch (Exception e) {
                    showErrorMessage(e.getMessage());
                    Log.e(TAG, e.getMessage(), e);
                }
            }

            setupBasePopups(getLayoutInflater(), false);

            quickPassCountdownTimer = new CountDownTimer(Long.MAX_VALUE, 1000) {

                public void onTick(long millisUntilFinished) {
                    checkQuickPassStatus();
                }

                public void onFinish() {
                    checkQuickPassStatus();
                }
            }.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        quickPassCountdownTimer.cancel();
        checkQuickPassStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if(result != null) {
            if(result.getContents() == null) {
                Log.d("MainActivity", "Cancelled scan");
                Snackbar.make(rootView, R.string.scan_cancel, Snackbar.LENGTH_LONG).show();
                if(!mDbHelper.hasIdentities()) {
                    startActivity(new Intent(this, StartActivity.class));
                }
            } else {
                final String serverData = Utils.readSQRLQRCodeAsString(data);
                communicationFlowHandler.setServerData(serverData);
                communicationFlowHandler.setUseSSL(serverData.startsWith("sqrl://"));

                Matcher sqrlMatcher = CommunicationHandler.sqrlPattern.matcher(serverData);
                if(!sqrlMatcher.matches()) {
                    showErrorMessage(R.string.scan_incorrect);
                    return;
                }

                final String domain = sqrlMatcher.group(1);
                final String queryLink = sqrlMatcher.group(2);

                try {
                    communicationFlowHandler.setQueryLink(queryLink);
                    communicationFlowHandler.setDomain(domain, queryLink);
                } catch (Exception e) {
                    showErrorMessage(e.getMessage());
                    Log.e(TAG, e.getMessage(), e);
                    return;
                }

                handler.postDelayed(() -> {
                    final TextView txtSite = loginPopupWindow.getContentView().findViewById(R.id.txtSite);
                    txtSite.setText(domain);

                    SQRLStorage storage = SQRLStorage.getInstance(SimplifiedActivity.this.getApplicationContext());
                    final TextView txtLoginPassword = loginPopupWindow.getContentView().findViewById(R.id.txtLoginPassword);
                    if(storage.hasQuickPass()) {
                        txtLoginPassword.setHint(getString(R.string.login_identity_quickpass, "" + storage.getHintLength()));
                    } else {
                        txtLoginPassword.setHint(R.string.login_identity_password);
                    }

                    showLoginPopup();

                    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && storage.hasBiometric()) {

                        BioAuthenticationCallback biometricCallback =
                                new BioAuthenticationCallback(SimplifiedActivity.this.getApplicationContext(), () -> {
                                    handler.post(() -> {
                                        hideLoginPopup();
                                        showProgressPopup();
                                    });
                                    communicationFlowHandler.addAction(CommunicationFlowHandler.Action.QUERY_WITHOUT_SUK_QRCODE);
                                    communicationFlowHandler.addAction(CommunicationFlowHandler.Action.LOGIN);

                                    communicationFlowHandler.setDoneAction(() -> {
                                        storage.clear();
                                        handler.post(() -> {
                                            hideProgressPopup();
                                            closeActivity();
                                        });
                                    });

                                    communicationFlowHandler.setErrorAction(() -> {
                                        storage.clear();
                                        handler.post(() -> hideProgressPopup());
                                    });

                                    communicationFlowHandler.handleNextAction();
                                });

                        BiometricPrompt bioPrompt = new BiometricPrompt.Builder(this)
                                .setTitle(getString(R.string.login_title))
                                .setSubtitle(domain)
                                .setDescription(getString(R.string.login_verify_domain_text))
                                .setNegativeButton(
                                    getString(R.string.button_cps_cancel),
                                    this.getMainExecutor(),
                                    (dialogInterface, i) -> {}
                                ).build();

                        CancellationSignal cancelSign = new CancellationSignal();
                        cancelSign.setOnCancelListener(() -> {});

                        try {
                            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                            keyStore.load(null);
                            KeyStore.Entry entry = keyStore.getEntry("quickPass", null);
                            Cipher decCipher = Cipher.getInstance("RSA/ECB/PKCS1PADDING"); //or try with "RSA"
                            decCipher.init(Cipher.DECRYPT_MODE, ((KeyStore.PrivateKeyEntry) entry).getPrivateKey());
                            bioPrompt.authenticate(new BiometricPrompt.CryptoObject(decCipher), cancelSign, this.getMainExecutor(), biometricCallback);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                }, 100);
            }
        }
    }

    private void checkQuickPassStatus() {
        SQRLStorage storage = SQRLStorage.getInstance(SimplifiedActivity.this.getApplicationContext());

        if (storage.hasQuickPass()) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

            long currentId = sharedPreferences.getLong(CURRENT_ID, 0);
            long quickPassExpirationTime = sharedPreferences.getLong("QUICKPASS_EXPIRATION_TIME_MILLIS" + currentId, -1);
            long duration = quickPassExpirationTime - System.currentTimeMillis();

            int hours = 0, minutes = 0, seconds = 0;

            if (duration > 0) {
                hours = (int)(duration / 3.6e+6);
                minutes = (int)((duration % 3.6e+6) / 60000 );
                seconds = (int) (((duration % 3.6e+6) % 60000 ) / 1000);
            }

            String countdown = String.format("%02d", hours) + ":" +
                    String.format("%02d", minutes) + ":" +
                    String.format("%02d", seconds);

            txtQuickPassTimeout.setText(countdown);
            quickPassLayout.setVisibility(View.VISIBLE);
        } else {
            if (quickPassCountdownTimer != null) quickPassCountdownTimer.cancel();
            quickPassLayout.setVisibility(View.GONE);
        }
    }
}
