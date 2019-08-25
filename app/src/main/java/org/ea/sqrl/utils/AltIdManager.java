package org.ea.sqrl.utils;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;

import org.ea.sqrl.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AltIdManager provides functionality for saving and managing
 * SQRL's so called "alternate identities".
 *
 * @author Alexander Hauser (alexhauser)
 */
public class AltIdManager {
    private static final String TAG = AltIdManager.class.getSimpleName();
    private static AltIdManager mInstance;
    private static Context mContext;
    private AltIdSelectedListener mAltIdSelectedListener = null;

    public interface AltIdSelectedListener {
        void onAltIdSelected(String altId);
    }

    private AltIdManager(Context context) {
        mContext = context;
    }

    /***
     * Retrieve the current AltIdManager instance.
     *
     * @param context       The context of the caller.
     * @return              The current AltIdManager instance.
     */
    public static AltIdManager getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new AltIdManager(context);
        }
        return mInstance;
    }

    /***
     * Gets a list of stored alternate ids for the specified identity id.
     *
     * @param identityId    The id of the corresponding identity. Specify -1 to return
     *                      all available alternate ids irrespective of the identity id.
     * @return              A list of alternate ids for the specified identity id.
     */
    public List<String> getAltIds(long identityId) {
        List<String> result = new ArrayList<>();
        Set<String> altIdsWithIdIndices = getAltIdsRaw();

        for (String entry : altIdsWithIdIndices ) {
            String[] tokens = entry.split(";", 2);
            if (tokens.length < 2) continue;
            long altIdIdentityId = Long.valueOf(tokens[0]);
            if (altIdIdentityId == identityId || identityId == -1) {
                if (!result.contains(tokens[1])) {
                    result.add(tokens[1]);
                }
            }
        }

        return result;
    }

    /***
     * Saves a new alternate id string for the given identity id.
     * Duplicates are being silently ignored.
     *
     * @param altId         The alternate id string to be saved.
     * @param identityId    The id of the corresponding identity. Specify -1
     *                      if the alt id should be valid for all identities.
     */
    public void saveAltId(String altId, long identityId) {
        Set<String> altIdsCopy = new HashSet<>(getAltIdsRaw());
        String newEntry = identityId + ";" + altId;

        boolean found = false;
        for (String entry : altIdsCopy) {
            if (entry.equals(newEntry)) {
                found = true;
            }
        }
        if (!found) {
            altIdsCopy.add(newEntry);
            saveAltIds(altIdsCopy);
        }
    }

    /***
     * Removes an alternate id string from shared preferences.
     *
     * @param altId         The alternate id string to be removed.
     * @param identityId    The id of the corresponding identity. Specify -1 if the
     *                      alt id should be removed from all identities.
     */
    public void removeAltId(String altId, long identityId) {
        Set<String> altIds = getAltIdsRaw();
        Set<String> altIdsCopy = new HashSet<>();

        boolean changed = false;

        for (String entry : altIds) {
            String[] tokens = entry.split(";", 2);
            if (tokens.length < 2) continue;

            long storedIdentityId = Long.valueOf(tokens[0]);
            String storedAltId = tokens[1];

            if (storedAltId.equals(altId)) {
                if (identityId == -1 || storedIdentityId == identityId) {
                    changed = true;
                    continue;
                }
            }

            altIdsCopy.add(entry);
        }

        if (changed) {
            saveAltIds(altIdsCopy);
        }
    }

    /***
     * Checks whether the given alternate id is already stored in shared preferences.
     *
     * @param altId         The alternate id string to be checked for.
     * @param identityId    The id of the corresponding identity. Specify -1 if the
     *                      identity id should be ignored in the search.
     * @return              True if an alt id was found, false otherwise.
     */
    public boolean hasAltId(String altId, long identityId) {
        Set<String> altIds = getAltIdsRaw();

        for (String entry : altIds) {
            String[] tokens = entry.split(";", 2);
            if (tokens.length < 2) continue;

            long storedIdentityId = Long.valueOf(tokens[0]);
            String storedAltId = tokens[1];

            if (storedAltId.equals(altId)) {
                if (identityId == -1 || storedIdentityId == identityId) {
                    return true;
                }
            }
        }

        return false;
    }

    /***
     * Checks whether or not there are alternate ids present for the given identity.
     *
     * @param identityId    The id of the identity for which alt ids should be checked.
     *                      Specify -1 if the identity id should be ignored in the search.
     * @return              True if an alt id was found, false otherwise.
     */
    public boolean hasAltIds(long identityId) {
        Set<String> altIds = getAltIdsRaw();

        for (String entry : altIds) {
            String[] tokens = entry.split(";", 2);
            if (tokens.length < 2) continue;

            long storedIdentityId = Long.valueOf(tokens[0]);
            if (identityId == -1 || storedIdentityId == identityId) {
                return true;
            }
        }

        return false;
    }

    /***
     * Display a UI dialog for choosing a stored alt-id
     *
     * @param identityId    The id of the corresponding identity. Specify -1
     *                      to list all available alt-ids.
     */
    public void showChooseAltIdDialog(Context context, long identityId) {
        List<String> altIds = getAltIds(identityId);
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setIcon(R.drawable.ic_id_management_menuitemcolor_24dp);
        dialogBuilder.setTitle(context.getResources().getString(R.string.choose_alt_id_dialog_title));
        dialogBuilder.setSingleChoiceItems(altIds.toArray(new CharSequence[0]),
                -1, (DialogInterface dialog, int item) -> {
                    if (mAltIdSelectedListener != null) {
                        mAltIdSelectedListener.onAltIdSelected(altIds.get(item));
                    }
                    dialog.dismiss();
                });
        AlertDialog alert = dialogBuilder.create();
        alert.show();
    }

    /**
     * Sets a listener that will be called when an alternate id was selected using the UI dialog.
     * @param listener The listener to call  when an alt-id was selected.
     */
    public void setAltIdSelectedListener(AltIdSelectedListener listener) {
        mAltIdSelectedListener = listener;
    }

    private Set<String> getAltIdsRaw() {
        return PreferenceManager.getDefaultSharedPreferences(mContext)
                .getStringSet(SqrlApplication.SHARED_PREF_ALT_IDS, new HashSet<>());
    }

    private void saveAltIds(Set<String> altIds) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = settings.edit();
        editor.putStringSet(SqrlApplication.SHARED_PREF_ALT_IDS, altIds);
        editor.apply();
    }

}