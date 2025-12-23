import * as admin from "firebase-admin";
import {onDocumentCreated} from "firebase-functions/v2/firestore";

admin.initializeApp();

// Define the structure of a User document in Firestore
interface User {
  fcmToken: string;
}

// Define the structure of a Message document in Firestore
interface Message {
  sender: string;
  recipient: string;
  text: string;
  timestamp: number;
}

export const sendPushNotification = onDocumentCreated("messages/{messageId}",
  async (event) => {
    const snap = event.data;
    if (!snap) {
      console.log("No data associated with the event");
      return;
    }
    const newMessage = snap.data() as Message;
    const recipientEmail = newMessage.recipient;

    // Get the recipient's user document to find their FCM token
    // eslint-disable-next-line max-len
    const userRef = admin.firestore().collection("users").doc(recipientEmail);
    const userDoc = await userRef.get();

    if (!userDoc.exists) {
      console.log("User document not found for recipient:", recipientEmail);
      return;
    }

    const recipientUser = userDoc.data() as User;
    const token = recipientUser.fcmToken;

    if (!token) {
      console.log("FCM token not found for user:", recipientEmail);
      return;
    }

    // Construct the notification payload
    const payload = {
      notification: {
        title: `New message from ${newMessage.sender}`,
        body: newMessage.text,
        sound: "default",
      },
      token: token,
    };

    // Send the push notification
    try {
      const response = await admin.messaging().send(payload);
      console.log("Successfully sent push notification:", response);
      return response;
    } catch (error) {
      console.error("Error sending push notification:", error);
      return;
    }
  });
