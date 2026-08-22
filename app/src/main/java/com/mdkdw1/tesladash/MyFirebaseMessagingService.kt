package com.mdkdw1.tesladash

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("TeslaDash", "New FCM Token: $token")
        MainActivity.injectFcmToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("TeslaDash", "FCM Message: ${remoteMessage.notification?.body}")
    }

    companion object {
        fun resetNotificationChannels(context: Context, pattern: String) {
            Log.d("TeslaDash", "Vibration pattern set: $pattern")
        }
    }
}
