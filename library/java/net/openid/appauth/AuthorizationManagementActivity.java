/*
 * Copyright 2016 The AppAuth for Android Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openid.appauth;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.VisibleForTesting;

import org.json.JSONException;

/**
 * Stores state and handles events related to the authorization flow. The activity is
 * started by {@link AuthorizationService#performAuthorizationRequest
 * AuthorizationService.performAuthorizationRequest}, and records all state pertinent to
 * the authorization request before invoking the authorization intent. It also functions
 * to control the back stack, ensuring that the authorization activity will not be reachable
 * via the back button after the flow completes.
 *
 * <p>The following diagram illustrates the operation of the activity:
 *
 * <pre>
 *                          Back Stack Towards Top
 *                +------------------------------------------>
 *
 * +------------+       +---------------+      +----------------+      +--------------+
 * |            |  (1)  |               | (2)  |                | (S1) |              |
 * | Initiating +------>| Authorization +----->| Authorization  +----->| Redirect URI |
 * |  Activity  |       |  Management   |      |   Activity     |      |   Receiver   |
 * |            |<------+   Activity    |<-----+ (e.g. browser) |      |   Activity   |
 * |            | (C2b) |               | (C1) |                |      |              |
 * +------------+       +-+--+----------+      +----------------+      +-------+------+
 *                        |  |  ^                                              |
 *                        |  |  |                                              |
 *                +-------+  |  |                      (S2)                    |
 *                |          |  +----------------------------------------------+
 *                |          |
 *                |          v (C2)
 *           (S3) |      +------------+
 *                |      |            |
 *                |      | Completion |
 *                |      |  Activity  |
 *                |      |            |
 *                |      +------------+
 *                |
 *                |      +-------------+
 *                |      |             |
 *                +----->| Cancelation |
 *                       |  Activity   |
 *                       |             |
 *                       +-------------+
 * </pre>
 *
 * <p>The process begins with an activity requesting that an authorization flow be started,
 * using {@link AuthorizationService#performAuthorizationRequest}.
 *
 * <ul>
 *   <li>Step 1: Using an intent derived from {@link #createStartIntent}, this activity is
 *       started. The state delivered in this intent is recorded for future use.
 *   <li>Step 2: The authorization intent, typically a browser tab, is started. At this point,
 *       depending on user action, we will either end up in a "completion" flow (S) or
 *       "cancelation flow" (C).
 *
 *   <li>C flow:
 *
 *     <ul>
 *       <li>Step C1: If the user presses the back button or otherwise causes the
 *           authorization activity to finish, the AuthorizationManagementActivity will be
 *           recreated or restarted.
 *       <li>Step C2a: If a cancelation PendingIntent was provided in the call to
 *           {@link AuthorizationService#performAuthorizationRequest}, then this is
 *           used to invoke a cancelation activity.
 *       <li>Step C2b: If no cancelation PendingIntent was provided (legacy behavior), then
 *           the AuthorizationManagementActivity simply finishes, returning control
 *           to the activity above it in the back stack (typically, the
 *           initiating activity).
 *     </ul>
 *   <li>S flow:
 *     <ul>
 *       <li>Step S1: The authorization activity completes with a success of failure, and sends
 *           this result to {@link RedirectUriReceiverActivity}.
 *       <li>Step S2: {@link RedirectUriReceiverActivity} extracts the forwarded data, and
 *           invokes AuthorizationManagementActivity using an intent derived from
 *           {@link #createResponseHandlingIntent}. This intent has flag CLEAR_TOP set, which
 *           will result in both the authorization activity and
 *           {@link RedirectUriReceiverActivity} being destroyed, if necessary, such that
 *           AuthorizationManagementActivity is once again at the top of the back stack.
 *       <li>Step S3: The pending intent provided to
 *           {@link AuthorizationService#performAuthorizationRequest} for completion of the
 *           authorization flow is invoked, providing the decoded
 *           {@link AuthorizationResponse} or {@link AuthorizationException} as appropriate.
 *           The AuthorizationManagementActivity finishes, removing itself from the back
 *           stack.
 *     </ul>
 * </ul>
 */
public class AuthorizationManagementActivity extends Activity {

    @VisibleForTesting
    static final String KEY_AUTH_INTENT = "authIntent";

    @VisibleForTesting
    static final String KEY_AUTH_REQUEST = "authRequest";

    @VisibleForTesting
    static final String KEY_COMPLETE_INTENT = "completeIntent";

    @VisibleForTesting
    static final String KEY_CANCEL_INTENT = "cancelIntent";

    @VisibleForTesting
    static final String KEY_AUTHORIZATION_STARTED = "authStarted";

    private Clock mClock = SystemClock.INSTANCE;

    private boolean mAuthorizationStarted = false;
    private Intent mAuthIntent;
    private AuthorizationRequest mAuthRequest;
    private PendingIntent mCompleteIntent;
    private PendingIntent mCancelIntent;

    /**
     * Creates an intent to start an authorization flow.
     * @param context the package context for the app.
     * @param request the authorization request which is to be sent.
     * @param authIntent the intent to be used to get authorization from the user.
     * @param completeIntent the intent to be sent when the flow completes.
     * @param cancelIntent the intent to be sent when the flow is canceled.
     */
    public static Intent createStartIntent(
            Context context,
            AuthorizationRequest request,
            Intent authIntent,
            PendingIntent completeIntent,
            PendingIntent cancelIntent) {
        Intent intent = createBaseIntent(context);
        intent.putExtra(KEY_AUTH_INTENT, authIntent);
        intent.putExtra(KEY_AUTH_REQUEST, request.jsonSerializeString());
        intent.putExtra(KEY_COMPLETE_INTENT, completeIntent);
        intent.putExtra(KEY_CANCEL_INTENT, cancelIntent);
        return intent;
    }

    /**
     * Creates an intent to handle the completion of an authorization flow. This restores
     * the original AuthorizationManagementActivity that was created at the start of the flow.
     * @param context the package context for the app.
     * @param responseUri the response URI, which carries the parameters describing the response.
     */
    public static Intent createResponseHandlingIntent(Context context, Uri responseUri) {
        Intent intent = createBaseIntent(context);
        intent.setData(responseUri);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    private static Intent createBaseIntent(Context context) {
        return new Intent(context, AuthorizationManagementActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            extractState(getIntent().getExtras());
        } else {
            extractState(savedInstanceState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        /*
         * If this is the first run of the activity, start the authorization intent.
         * Note that we do not finish the activity at this point, in order to remain on the back
         * stack underneath the authorization activity.
         */

        if (!mAuthorizationStarted) {
            startActivity(mAuthIntent);
            mAuthorizationStarted = true;
            return;
        }

        /*
         * On a subsequent run, it must be determined whether we have returned to this activity
         * due to an OAuth2 redirect, or the user canceling the authorization flow. This can
         * be done by checking whether a response URI is available, which would be provided by
         * RedirectUriReceiverActivity. If it is not, we have returned here due to the user
         * pressing the back button, or the authorization activity finishing without
         * RedirectUriReceiverActivity having been invoked - this can occur when the user presses
         * the back button, or closes the browser tab.
         */

        if (getIntent().getData() != null) {
            handleAuthorizationComplete();
        } else {
            handleAuthorizationCanceled();
        }
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_AUTHORIZATION_STARTED, mAuthorizationStarted);
        outState.putParcelable(KEY_AUTH_INTENT, mAuthIntent);
        outState.putString(KEY_AUTH_REQUEST, mAuthRequest.jsonSerializeString());
        outState.putParcelable(KEY_COMPLETE_INTENT, mCompleteIntent);
        outState.putParcelable(KEY_CANCEL_INTENT, mCancelIntent);
    }

    private void handleAuthorizationComplete() {
        Uri responseUri = getIntent().getData();
        Intent responseData = extractResponseData(responseUri);
        if (responseData == null) {
            Logger.error("Failed to extract OAuth2 response from redirect");
            return;
        }

        Logger.debug("Authorization complete - invoking completion intent");
        try {
            mCompleteIntent.send(this, 0, responseData);
        } catch (CanceledException ex) {
            Logger.error("Failed to send completion intent", ex);
        }
    }

    private void handleAuthorizationCanceled() {
        Logger.debug("Authorization flow canceled by user");
        if (mCancelIntent != null) {
            try {
                mCancelIntent.send();
            } catch (CanceledException ex) {
                Logger.error("Failed to send cancel intent", ex);
            }
        } else {
            Logger.debug("No cancel intent set - will return to previous activity");
        }
    }

    private void extractState(Bundle state) {
        if (state == null) {
            throw new IllegalStateException("No state to extract");
        }

        mAuthIntent = state.getParcelable(KEY_AUTH_INTENT);
        mAuthorizationStarted = state.getBoolean(KEY_AUTHORIZATION_STARTED, false);
        try {
            String authRequestJson = state.getString(KEY_AUTH_REQUEST, null);
            mAuthRequest = authRequestJson != null
                    ? AuthorizationRequest.jsonDeserialize(authRequestJson)
                    : null;
        } catch (JSONException ex) {
            throw new IllegalStateException("Unable to deserialize authorization request", ex);
        }
        mCompleteIntent = state.getParcelable(KEY_COMPLETE_INTENT);
        mCancelIntent = state.getParcelable(KEY_CANCEL_INTENT);
    }

    private Intent extractResponseData(Uri responseUri) {
        if (responseUri.getQueryParameterNames().contains(AuthorizationException.PARAM_ERROR)) {
            return AuthorizationException.fromOAuthRedirect(responseUri).toIntent();
        } else {
            AuthorizationResponse response = new AuthorizationResponse.Builder(mAuthRequest)
                    .fromUri(responseUri, mClock)
                    .build();
            return response.toIntent();
        }
    }
}
