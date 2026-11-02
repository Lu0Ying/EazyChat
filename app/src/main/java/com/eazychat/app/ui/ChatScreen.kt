package com.eazychat.app.ui
// [Goose] Bridge file share events to ViewModel via dispatcher is installed in ChatScreen composition

// [Goose] Installing FileShareDispatcher handler in ChatScreen to forward file sends to ViewModel


import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.eazychat.app.model.BitchatMessage
import com.eazychat.app.ui.media.FullScreenImageViewer

/**
 * Main ChatScreen - REFACTORED to use component-based architecture
 * This is now a coordinator that orchestrates the following UI components:
 * - ChatHeader: App bar, navigation, peer counter
 * - MessageComponents: Message display and formatting
 * - InputComponents: Message input and command suggestions
 * - SidebarComponents: Navigation drawer with channels and people
 * - AboutSheet: App info and password prompts
 * - ChatUIUtils: Utility functions for formatting and colors
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val messages by viewModel.messages.observeAsState(emptyList())
    val connectedPeers by viewModel.connectedPeers.observeAsState(emptyList())
    val nickname by viewModel.nickname.observeAsState("")
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.observeAsState()
    val currentChannel by viewModel.currentChannel.observeAsState()
    val joinedChannels by viewModel.joinedChannels.observeAsState(emptySet())
    val hasUnreadChannels by viewModel.unreadChannelMessages.observeAsState(emptyMap())
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.observeAsState(emptySet())
    val privateChats by viewModel.privateChats.observeAsState(emptyMap())
    val channelMessages by viewModel.channelMessages.observeAsState(emptyMap())
    val showSidebar by viewModel.showSidebar.observeAsState(false)
    val showCommandSuggestions by viewModel.showCommandSuggestions.observeAsState(false)
    val commandSuggestions by viewModel.commandSuggestions.observeAsState(emptyList())
    val showMentionSuggestions by viewModel.showMentionSuggestions.observeAsState(false)
    val mentionSuggestions by viewModel.mentionSuggestions.observeAsState(emptyList())
    val showAppInfo by viewModel.showAppInfo.observeAsState(false)

    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }

    var showUserSheet by remember { mutableStateOf(false) }
    var selectedUserForSheet by remember { mutableStateOf("") }
    var selectedMessageForSheet by remember { mutableStateOf<BitchatMessage?>(null) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    var viewerImagePaths by remember { mutableStateOf(emptyList<String>()) }
    var initialViewerIndex by remember { mutableStateOf(0) }
    var forceScrollToBottom by remember { mutableStateOf(false) }
    var isScrolledUp by remember { mutableStateOf(false) }

    // Show password dialog when needed
    LaunchedEffect(showPasswordPrompt) {
        showPasswordDialog = showPasswordPrompt
    }

    val isConnected by viewModel.isConnected.observeAsState(false)
    val passwordPromptChannel by viewModel.passwordPromptChannel.observeAsState(null)

    // Determine what messages to show based on current context
    val displayMessages = when {
        selectedPrivatePeer != null -> privateChats[selectedPrivatePeer] ?: emptyList()
        currentChannel != null -> channelMessages[currentChannel] ?: emptyList()
        else -> messages // Mesh timeline
    }

    // Show media buttons by default
    val showMediaButtons = true

    // Use WindowInsets to handle keyboard properly
    Scaffold(
        topBar = {
            ChatTopAppBar(
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                connectedPeers = connectedPeers,
                isConnected = isConnected,
                viewModel = viewModel,
                onSidebarToggle = { viewModel.showSidebar() },
                onShowAppInfo = { viewModel.showAppInfo() },
                onPanicClear = { viewModel.panicClearAllData() }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) {
        paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.ime) // This handles keyboard insets
                    .windowInsetsPadding(WindowInsets.navigationBars) // Add bottom padding when keyboard is not expanded
            ) {
                // Messages area - takes up available space, will compress when keyboard appears
                MessagesList(
                    messages = displayMessages,
                    currentUserNickname = nickname,
                    meshService = viewModel.meshService,
                    modifier = Modifier.weight(1f),
                    forceScrollToBottom = forceScrollToBottom,
                    onScrolledUpChanged = { isUp -> isScrolledUp = isUp },
                    onNicknameClick = { fullSenderName ->
                        // Single click - mention user in text input
                        val currentText = messageText.text
                        
                        // Extract base nickname and hash suffix from full sender name
                        val (baseName, hashSuffix) = splitSuffix(fullSenderName)
                        
                        // Regular chat - just the base nickname
                        val mentionText = "@$baseName"
                        
                        val newText = when {
                            currentText.isEmpty() -> "$mentionText "
                            currentText.endsWith(" ") -> "$currentText$mentionText "
                            else -> "$currentText $mentionText "
                        }
                        
                        messageText = TextFieldValue(
                            text = newText,
                            selection = TextRange(newText.length)
                        )
                    },
                    onMessageLongPress = { message ->
                        // Message long press - open user action sheet with message context
                        // Extract base nickname from message sender (contains all necessary info)
                        val (baseName, _) = splitSuffix(message.sender)
                        selectedUserForSheet = baseName
                        selectedMessageForSheet = message
                        showUserSheet = true
                    },
                    onCancelTransfer = { msg ->
                        viewModel.cancelMediaSend(msg.id)
                    },
                    onImageClick = { currentPath, allImagePaths, initialIndex ->
                        viewerImagePaths = allImagePaths
                        initialViewerIndex = initialIndex
                        showFullScreenImageViewer = true
                    }
                )
                
                // Bridge file share from lower-level input to ViewModel
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    com.eazychat.app.ui.events.FileShareDispatcher.setHandler { peer, channel, path ->
                        viewModel.sendFileNote(peer, channel, path)
                    }
                }

                ChatInputSection(
                    messageText = messageText,
                    onMessageTextChange = { newText: TextFieldValue ->
                        messageText = newText
                        viewModel.updateCommandSuggestions(newText.text)
                        viewModel.updateMentionSuggestions(newText.text)
                    },
                    onSend = {
                        if (messageText.text.trim().isNotEmpty()) {
                            viewModel.sendMessage(messageText.text.trim())
                            messageText = TextFieldValue("")
                            forceScrollToBottom = !forceScrollToBottom // Toggle to trigger scroll
                        }
                    },
                    onSendVoiceNote = { peer, onionOrChannel, path ->
                        viewModel.sendVoiceNote(peer, onionOrChannel, path)
                    },
                    onSendImageNote = { peer, onionOrChannel, path ->
                        viewModel.sendImageNote(peer, onionOrChannel, path)
                    },
                    onSendFileNote = { peer, onionOrChannel, path ->
                        viewModel.sendFileNote(peer, onionOrChannel, path)
                    },
                    onSendSos = { note ->
                        viewModel.sendSos(note)
                    },
                    
                    showCommandSuggestions = showCommandSuggestions,
                    commandSuggestions = commandSuggestions,
                    showMentionSuggestions = showMentionSuggestions,
                    mentionSuggestions = mentionSuggestions,
                    onCommandSuggestionClick = { suggestion: CommandSuggestion ->
                                val commandText = viewModel.selectCommandSuggestion(suggestion)
                                messageText = TextFieldValue(
                                    text = commandText,
                                    selection = TextRange(commandText.length)
                                )
                            },
                            onMentionSuggestionClick = { mention: String ->
                                val mentionText = viewModel.selectMentionSuggestion(mention, messageText.text)
                                messageText = TextFieldValue(
                                    text = mentionText,
                                    selection = TextRange(mentionText.length)
                                )
                            },
                            selectedPrivatePeer = selectedPrivatePeer,
                            currentChannel = currentChannel,
                            nickname = nickname,
                            colorScheme = colorScheme,
                            showMediaButtons = showMediaButtons
                        )
                    }

            // Scroll-to-bottom floating button
            AnimatedVisibility(
                visible = isScrolledUp && !showSidebar,
                enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 80.dp)
                    .zIndex(1.5f)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .windowInsetsPadding(WindowInsets.ime)
            ) {
                Surface(
                    shape = CircleShape,
                    color = colorScheme.primary,
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp
                ) {
                    IconButton(onClick = { forceScrollToBottom = !forceScrollToBottom }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowDownward,
                            contentDescription = stringResource(com.eazychat.app.R.string.cd_scroll_to_bottom),
                            tint = colorScheme.onPrimary
                        )
                    }
                }
            }

            val alpha by animateFloatAsState(
                targetValue = if (showSidebar) 0.5f else 0f,
                animationSpec = tween(
                    durationMillis = 300,
                    easing = EaseOutCubic
                ), label = "overlayAlpha"
            )

            // Only render the background if it's visible
            if (alpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = alpha))
                        .clickable { viewModel.hideSidebar() }
                        .zIndex(1f)
                )
            }

            AnimatedVisibility(
                visible = showSidebar,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300, easing = EaseOutCubic)
                ) + fadeIn(animationSpec = tween(300)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250, easing = EaseInCubic)
                ) + fadeOut(animationSpec = tween(250)),
                modifier = Modifier.zIndex(2f)
            ) {
                SidebarOverlay(
                    viewModel = viewModel,
                    onDismiss = { viewModel.hideSidebar() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // Full-screen image viewer - separate from other sheets to allow image browsing without navigation
    if (showFullScreenImageViewer) {
        FullScreenImageViewer(
            imagePaths = viewerImagePaths,
            initialIndex = initialViewerIndex,
            onClose = { showFullScreenImageViewer = false }
        )
    }

    // Dialogs and Sheets
    ChatDialogs(
        showPasswordDialog = showPasswordDialog,
        passwordPromptChannel = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = { passwordInput = it },
        onPasswordConfirm = {
            if (passwordInput.isNotEmpty()) {
                val success = viewModel.joinChannel(passwordPromptChannel!!, passwordInput)
                if (success) {
                    showPasswordDialog = false
                    passwordInput = ""
                }
            }
        },
        onPasswordDismiss = {
            showPasswordDialog = false
            passwordInput = ""
        },
        showAppInfo = showAppInfo,
        onAppInfoDismiss = { viewModel.hideAppInfo() },
        showUserSheet = showUserSheet,
        onUserSheetDismiss = { 
            showUserSheet = false
            selectedMessageForSheet = null // Reset message when dismissing
        },
        selectedUserForSheet = selectedUserForSheet,
        selectedMessageForSheet = selectedMessageForSheet,
        viewModel = viewModel
    )
}

@Composable
private fun ChatInputSection(
    messageText: TextFieldValue,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendVoiceNote: (String?, String?, String) -> Unit,
    onSendImageNote: (String?, String?, String) -> Unit,
    onSendFileNote: (String?, String?, String) -> Unit,
    onSendSos: (String?) -> Unit,
    showCommandSuggestions: Boolean,
    commandSuggestions: List<CommandSuggestion>,
    showMentionSuggestions: Boolean,
    mentionSuggestions: List<String>,
    onCommandSuggestionClick: (CommandSuggestion) -> Unit,
    onMentionSuggestionClick: (String) -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    colorScheme: ColorScheme,
    showMediaButtons: Boolean
) {
    Column {
        // Command suggestions box
        if (showCommandSuggestions && commandSuggestions.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceVariant
                )
            ) {
                CommandSuggestionsBox(
                    suggestions = commandSuggestions,
                    onSuggestionClick = onCommandSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        // Mention suggestions box
        if (showMentionSuggestions && mentionSuggestions.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceVariant
                )
            ) {
                MentionSuggestionsBox(
                    suggestions = mentionSuggestions,
                    onSuggestionClick = onMentionSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = colorScheme.surface
            )
        ) {
            MessageInput(
                value = messageText,
                onValueChange = onMessageTextChange,
                onSend = onSend,
                onSendVoiceNote = onSendVoiceNote,
                onSendImageNote = onSendImageNote,
                onSendFileNote = onSendFileNote,
                onSendSos = onSendSos,
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                showMediaButtons = showMediaButtons,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopAppBar(
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    connectedPeers: List<String>,
    isConnected: Boolean,
    viewModel: ChatViewModel,
    onSidebarToggle: () -> Unit,
    onShowAppInfo: () -> Unit,
    onPanicClear: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    TopAppBar(
        title = {
            ChatHeaderContent(
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                viewModel = viewModel,
                onBackClick = {
                    when {
                        selectedPrivatePeer != null -> viewModel.endPrivateChat()
                        currentChannel != null -> viewModel.switchToChannel(null)
                    }
                },
                onSidebarClick = onSidebarToggle,
                onTripleClick = onPanicClear,
                onShowAppInfo = onShowAppInfo
            )
        },
        actions = {
            Row(
                modifier = Modifier.padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connection status indicator
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (isConnected) colorScheme.primary else colorScheme.error,
                            shape = CircleShape
                        )
                        .padding(end = 4.dp)
                )
                
                // Peer count
                Text(
                    text = "${connectedPeers.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colorScheme.surface,
            titleContentColor = colorScheme.onSurface,
            actionIconContentColor = colorScheme.onSurfaceVariant
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDialogs(
    showPasswordDialog: Boolean,
    passwordPromptChannel: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirm: () -> Unit,
    onPasswordDismiss: () -> Unit,
    showAppInfo: Boolean,
    onAppInfoDismiss: () -> Unit,
    showUserSheet: Boolean,
    onUserSheetDismiss: () -> Unit,
    selectedUserForSheet: String,
    selectedMessageForSheet: BitchatMessage?,
    viewModel: ChatViewModel
) {
    // Password dialog
    PasswordPromptDialog(
        show = showPasswordDialog,
        channelName = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = onPasswordChange,
        onConfirm = onPasswordConfirm,
        onDismiss = onPasswordDismiss
    )

    // About sheet
    AboutSheet(
        isPresented = showAppInfo,
        onDismiss = onAppInfoDismiss,
        onShowDebug = { /* Debug settings not available */ }
    )
    
    // User action sheet
    if (showUserSheet) {
        ChatUserSheet(
            isPresented = showUserSheet,
            onDismiss = onUserSheetDismiss,
            targetNickname = selectedUserForSheet,
            selectedMessage = selectedMessageForSheet,
            viewModel = viewModel
        )
    }
}
