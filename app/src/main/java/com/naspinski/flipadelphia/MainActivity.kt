package com.naspinski.flipadelphia

import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.ktx.messaging
import com.naspinski.flipadelphia.ui.theme.FlipadelphiaTheme
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlipadelphiaTheme {
                FlipadelphiaApp()
            }
        }
    }
}

@Composable
fun FlipadelphiaApp() {
    val auth: FirebaseAuth = Firebase.auth
    var user by remember { mutableStateOf(auth.currentUser) }

    DisposableEffect(auth) {
        val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            user = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(authStateListener)
        onDispose {
            auth.removeAuthStateListener(authStateListener)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (user == null) {
            LoginScreen()
        } else {
            MessagingScreen(
                user = user,
                onLogout = { auth.signOut() }
            )
        }
    }
}

data class User(val fcmToken: String = "")

@Composable
fun LoginScreen(modifier: Modifier = Modifier) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val auth: FirebaseAuth = Firebase.auth
    val db = Firebase.firestore

    fun saveToken(firebaseUser: FirebaseUser) {
        Firebase.messaging.token.addOnCompleteListener { tokenTask ->
            if (!tokenTask.isSuccessful) {
                Log.w("LoginScreen", "Fetching FCM token failed", tokenTask.exception)
                return@addOnCompleteListener
            }
            val token = tokenTask.result
            val user = User(token)
            db.collection("users").document(firebaseUser.uid).set(user)
                .addOnSuccessListener { Log.d("LoginScreen", "FCM token saved successfully.") }
                .addOnFailureListener { e -> Log.e("LoginScreen", "Error saving FCM token", e) }
        }
    }

    Column(modifier = modifier
        .fillMaxSize()
        .padding(16.dp), verticalArrangement = Arrangement.Center) {
        TextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        TextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        task.result?.user?.let { saveToken(it) }
                    } else {
                        Log.w("LoginScreen", "signInWithEmail:failure", task.exception)
                    }
                }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Login")
        }
        Button(onClick = {
            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        task.result?.user?.let { saveToken(it) }
                    } else {
                        Log.w("LoginScreen", "createUserWithEmail:failure", task.exception)
                    }
                }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Register")
        }
    }
}

data class Message(
    val sender: String = "",
    val recipient: String = "",
    val text: String = "",
    val timestamp: Any? = null
) {
    fun getTimestampAsDate(): Date? {
        return when (timestamp) {
            is com.google.firebase.Timestamp -> timestamp.toDate()
            is Date -> timestamp
            is Long -> Date(timestamp)
            else -> null
        }
    }
}

@Composable
fun Query.listenAsState(): State<List<Message>> {
    val messagesFlow = callbackFlow {
        val listenerRegistration = addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.w("FirestoreQuery", "Listen failed.", e)
                close(e)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val messages = snapshot.documents.mapNotNull { doc ->
                    try {
                        val sender = doc.getString("sender") ?: ""
                        val recipient = doc.getString("recipient") ?: ""
                        val text = doc.getString("text") ?: ""
                        val timestampObject = doc.get("timestamp")
                        val date = when (timestampObject) {
                            is com.google.firebase.Timestamp -> timestampObject.toDate()
                            is Long -> Date(timestampObject)
                            is Date -> timestampObject
                            else -> null
                        }
                        Message(sender, recipient, text, date)
                    } catch (ex: Exception) {
                        Log.e("Firestore Deserialization", "Error converting document", ex)
                        null
                    }
                }
                trySend(messages)
            }
        }
        awaitClose { listenerRegistration.remove() }
    }
    return messagesFlow.collectAsState(initial = emptyList())
}


@Composable
fun MessagingScreen(
    modifier: Modifier = Modifier,
    user: FirebaseUser?,
    onLogout: () -> Unit
) {
    val currentUserEmail = user?.email ?: "Unknown User"
    var isInputVisible by remember { mutableStateOf(false) }
    val db = Firebase.firestore

    val query = remember(currentUserEmail) {
        db.collection("messages")
            .whereEqualTo("recipient", currentUserEmail)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(1)
    }

    val receivedMessages by query.listenAsState()

    Scaffold(
        modifier = modifier.background(Color.Black),
        floatingActionButton = {
            FloatingActionButton(onClick = { isInputVisible = !isInputVisible }) {
                if (isInputVisible) {
                    Icon(Icons.Default.Clear, contentDescription = "Close Message")
                } else {
                    Icon(Icons.Default.Add, contentDescription = "New Message")
                }
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Logged in as: $currentUserEmail",
                    textAlign = TextAlign.Center,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onLogout) {
                    Text("Logout", color = Color.Gray)
                }
            }
        }
    ) { innerPadding ->
        MessageContent(
            modifier = Modifier.padding(innerPadding),
            messages = receivedMessages,
            isInputVisible = isInputVisible,
            onSendMessage = { isInputVisible = false }
        )
    }
}

@Composable
fun MessageContent(
    modifier: Modifier = Modifier,
    messages: List<Message>,
    isInputVisible: Boolean,
    onSendMessage: () -> Unit
) {
    var recipient by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val db = Firebase.firestore
    val context = LocalContext.current
    val currentUserEmail = Firebase.auth.currentUser?.email ?: "Unknown User"

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val charWidth = 28 // dp, includes spacer
            val charHeight = 42 // dp, includes spacer
            val numCols = (maxWidth.value / charWidth).toInt()
            val numRows = (maxHeight.value / charHeight).toInt()

            val currentMessage = messages.firstOrNull()

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val textToShow = currentMessage?.text?.uppercase() ?: "AWAITING MESSAGE..."
                val lines = mutableListOf<String>()
                val words = textToShow.split(" ")
                var currentLine = ""
                words.forEach { word ->
                    if (currentLine.length + word.length + 1 <= numCols - 2) {
                        currentLine += "$word "
                    } else {
                        lines.add(currentLine.trim())
                        currentLine = "$word "
                    }
                }
                lines.add(currentLine.trim())

                val chunks = lines.take(numRows)

                val topPadding = (numRows - chunks.size) / 2
                val bottomPadding = numRows - chunks.size - topPadding

                if (currentMessage != null) {
                    MessageHeader(msg = currentMessage)
                }

                repeat(topPadding.coerceAtLeast(0)) {
                    CustomSplitFlapText(text = " ".repeat(numCols))
                }
                chunks.forEach { line ->
                    val padding = (numCols - line.length) / 2
                    val paddedLine = " ".repeat(padding) + line + " ".repeat(numCols - line.length - padding)
                    CustomSplitFlapText(text = paddedLine)
                }
                repeat(bottomPadding.coerceAtLeast(0)) {
                    CustomSplitFlapText(text = " ".repeat(numCols))
                }
            }
        }

        AnimatedVisibility(
            visible = isInputVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextField(value = recipient, onValueChange = { recipient = it }, label = { Text("Recipient") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                TextField(value = message, onValueChange = { message = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val emailMatcher = Patterns.EMAIL_ADDRESS.matcher(recipient)
                        if (recipient.isBlank()) {
                            Toast.makeText(context, "Recipient cannot be blank", Toast.LENGTH_SHORT).show()
                        } else if (!emailMatcher.matches()) {
                            Toast.makeText(context, "Invalid recipient email format", Toast.LENGTH_SHORT).show()
                        } else if (message.isBlank()) {
                            Toast.makeText(context, "Message cannot be blank", Toast.LENGTH_SHORT).show()
                        } else {
                            val newMessage = hashMapOf(
                                "sender" to currentUserEmail,
                                "recipient" to recipient,
                                "text" to message,
                                "timestamp" to FieldValue.serverTimestamp()
                            )
                            db.collection("messages").add(newMessage).addOnSuccessListener {
                                onSendMessage()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
fun MessageHeader(msg: Message) {
    val formattedTime = remember(msg.getTimestampAsDate()) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        msg.getTimestampAsDate()?.let { sdf.format(it) } ?: "..."
    }
    Text(
        text = "${msg.sender.uppercase()} | $formattedTime",
        fontFamily = FontFamily.Default,
        color = Color.Gray,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

@Composable
fun CustomSplitFlapChar(char: Char) {
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(40.dp)
            .background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = char,
            label = "CharFlip"
        ) { targetChar ->
            Text(
                text = if (targetChar == ' ') "" else targetChar.toString(),
                fontFamily = FontFamily.Monospace,
                fontSize = 24.sp,
                color = Color(0xFFF0F0F0),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun CustomSplitFlapText(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        text.forEach { char ->
            CustomSplitFlapChar(char = char)
            Spacer(modifier = Modifier.width(2.dp))
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    FlipadelphiaTheme {
        LoginScreen()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun VestaboardMessagePreview() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CustomSplitFlapText(text = "HELLO WORLD")
    }
}