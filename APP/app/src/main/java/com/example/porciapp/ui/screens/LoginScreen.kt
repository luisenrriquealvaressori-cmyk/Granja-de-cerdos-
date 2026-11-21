package com.example.porciapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.porciapp.R
import com.example.porciapp.data.auth.SessionManager
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
import com.example.porciapp.data.net.ApiClient
import com.example.porciapp.data.net.ApiResult
import com.example.porciapp.ui.components.TactileButton
import com.example.porciapp.ui.components.TactileCard
import com.example.porciapp.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onLoginSuccess: (UserRole, UserProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sessionManager = remember { SessionManager.get(context) }
    val apiClient = remember { ApiClient(sessionManager) }

    var userName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // Diálogos de configuración de servidor y cambio obligatorio de clave
    val currentServerUrl by sessionManager.serverUrlFlow.collectAsState()
    val isCloudServer = currentServerUrl.startsWith("https://", ignoreCase = true)
    var showServerConfigDialog by remember { mutableStateOf(false) }
    var showPasswordChangeDialog by remember { mutableStateOf(false) }
    var serverUrlInput by remember { mutableStateOf(currentServerUrl) }

    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    fun performLogin() {
        if (userName.isBlank()) {
            errorMessage = "Ingresa tu nombre de usuario o rol"
            return
        }
        if (password.isBlank()) {
            errorMessage = "Ingresa tu contraseña de acceso"
            return
        }

        isLoading = true
        errorMessage = null

        coroutineScope.launch {
            val activeServerUrl = sessionManager.serverUrl()
            when (val result = apiClient.login(userName.trim(), password)) {
                is ApiResult.Ok -> {
                    val session = result.value
                    if (session.debeCambiarPassword) {
                        isLoading = false
                        showPasswordChangeDialog = true
                    } else {
                        isLoading = false
                        onLoginSuccess(session.rol, session.toProfile())
                    }
                }
                is ApiResult.Fail -> {
                    isLoading = false
                    errorMessage = when (result.kind) {
                        ApiResult.Kind.NETWORK -> {
                            if (activeServerUrl.contains("onrender.com", ignoreCase = true) ||
                                activeServerUrl.startsWith("https://", ignoreCase = true)
                            ) {
                                "No se pudo conectar con $activeServerUrl. Verifica tus datos/Wi-Fi o reintenta en unos segundos mientras despierta el servidor en Render."
                            } else {
                                "Servidor local no detectado en $activeServerUrl. Verifica la red Wi-Fi o pulsa en 'Servidor ⚙️' para cambiar a la nube Render."
                            }
                        }
                        ApiResult.Kind.UNAUTHORIZED -> "Credenciales incorrectas. Verifica el usuario y la contraseña."
                        ApiResult.Kind.PASSWORD_CHANGE_REQUIRED -> {
                            showPasswordChangeDialog = true
                            "Es obligatorio actualizar tu contraseña para continuar."
                        }
                        ApiResult.Kind.LOCKED -> "Demasiados intentos fallidos. Espera unos momentos antes de reintentar."
                        else -> result.message
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = FieldBackground
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 1. Logo Badge Central con Identidad Rural Moderna
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF283A13))
                        .border(BorderStroke(1.5.dp, FieldPrimary.copy(alpha = 0.55f)), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "Logo Porky Gestión",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Títulos y Contexto de Granja
            Text(
                text = "GRANJA SAN JOSÉ",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = FieldPrimary,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Porky Gestión • Control Porcino por Rol",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = FieldOnSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Chip de Estado de Conectividad con botón de Configurar Servidor
            Surface(
                color = FieldSurfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, FieldDarkBorder.copy(alpha = 0.4f)),
                modifier = Modifier.clickable {
                    serverUrlInput = currentServerUrl
                    showServerConfigDialog = true
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isCloudServer) FieldGreenPositive else FieldOrangeWarning)
                    )
                    Icon(
                        imageVector = if (isCloudServer) Icons.Default.CloudQueue else Icons.Default.Dns,
                        contentDescription = null,
                        tint = FieldPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    val displayHost = currentServerUrl
                        .removePrefix("https://")
                        .removePrefix("http://")
                    Text(
                        text = if (isCloudServer) "Nube: $displayHost" else "Local: $displayHost",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = FieldOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Ajustar Servidor",
                        tint = FieldOnSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Formulario de Credenciales
            TactileCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = FieldSurfaceContainerLowest,
                shadowOffset = 3.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = FieldPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ACCESO SEGURO",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldOnSurface
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(FieldPrimary.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "JWT Bearer",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = FieldPrimary
                            )
                        }
                    }

                    // Campo Nombre de Usuario o Rol
                    OutlinedTextField(
                        value = userName,
                        onValueChange = {
                            userName = it
                            errorMessage = null
                        },
                        label = { Text("Nombre de Usuario o Rol") },
                        placeholder = { Text("admin, vet u operario") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = FieldPrimary
                            )
                        },
                        trailingIcon = {
                            if (userName.isNotBlank()) {
                                IconButton(onClick = { userName = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Campo Contraseña con Toggle de Visibilidad
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        label = { Text("Contraseña de Acceso") },
                        placeholder = { Text("PIN o Contraseña") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = FieldPrimary
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (passwordVisible) "Ocultar" else "Mostrar"
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                performLogin()
                            }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Mensaje de Error si aplica
                    if (errorMessage != null) {
                        Surface(
                            color = FieldErrorContainer,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, FieldError)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = FieldOnErrorContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errorMessage!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FieldOnErrorContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Botón Principal de Entrada
                    TactileButton(
                        onClick = { performLogin() },
                        text = if (isLoading) "Conectando con servidor..." else "Iniciar Turno",
                        icon = if (isLoading) Icons.Default.HourglassEmpty else Icons.Default.Login,
                        backgroundColor = FieldPrimaryContainer,
                        contentColor = FieldOnPrimary,
                        minHeight = 56.dp,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )

                    if (isLoading && currentServerUrl.contains("onrender.com", ignoreCase = true)) {
                        Text(
                            text = "Si el servidor en Render estaba inactivo, el primer acceso puede tardar hasta 40 s.",
                            style = MaterialTheme.typography.bodySmall,
                            color = FieldOnSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Selección rápida de usuario por rol (requiere ingresar contraseña)
            TactileCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = FieldSurfaceContainerLowest,
                shadowOffset = 2.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = null,
                            tint = FieldPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SELECCIÓN RÁPIDA DE USUARIO",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = FieldOnSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val rolesQuick = listOf(
                            Triple("operario", "Operario", "Alimento y Corrales" to RoleOperarioAccent),
                            Triple("vet", "Veterinario", "Sanidad y Partos" to RoleVeterinarioAccent),
                            Triple("admin", "Admin", "Gestión y Costos" to RoleAdminAccent)
                        )
                        rolesQuick.forEach { (userKey, label, meta) ->
                            val (subLabel, accentColor) = meta
                            val isSelected = userName.equals(userKey, ignoreCase = true)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) accentColor else FieldSurfaceContainer,
                                border = BorderStroke(1.dp, if (isSelected) accentColor else FieldOutlineVariant),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        userName = userKey
                                        errorMessage = null
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (isSelected) Color.White else accentColor,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = subLabel,
                                        fontSize = 9.sp,
                                        color = if (isSelected) Color.White.copy(alpha = 0.9f) else FieldOnSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Nota de Seguridad y Sincronización
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = FieldPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Autenticación segura con cifrado AES-256 Keystore & SQLite Outbox",
                    style = MaterialTheme.typography.labelSmall,
                    color = FieldOnSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }
        }
    }

    // Modal para Configurar URL del Servidor
    if (showServerConfigDialog) {
        Dialog(onDismissRequest = { showServerConfigDialog = false }) {
            TactileCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = FieldSurfaceContainerLowest
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = null,
                            tint = FieldPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Configuración del Servidor",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = FieldOnSurface
                        )
                    }

                    Text(
                        text = "Indica la dirección del servidor AgroPig (Nube Render HTTPS o servidor local):",
                        style = MaterialTheme.typography.bodySmall,
                        color = FieldOnSurfaceVariant
                    )

                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { serverUrlInput = it },
                        label = { Text("URL del Servidor") },
                        placeholder = { Text(SessionManager.DEFAULT_SERVER) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(
                            onClick = { serverUrlInput = SessionManager.DEFAULT_SERVER },
                            label = {
                                Text(
                                    "Nube Render (${SessionManager.DEFAULT_SERVER.removePrefix("https://")})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.CloudQueue,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AssistChip(
                                onClick = { serverUrlInput = SessionManager.LEGACY_LAN_SERVER },
                                label = { Text("Red Local", fontSize = 10.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            AssistChip(
                                onClick = { serverUrlInput = "http://10.0.2.2:5153" },
                                label = { Text("Emulador", fontSize = 10.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showServerConfigDialog = false }) {
                            Text("Cancelar")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                sessionManager.setServerUrl(serverUrlInput)
                                showServerConfigDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = FieldPrimary)
                        ) {
                            Text("Guardar")
                        }
                    }
                }
            }
        }
    }

    // Modal para Cambio Obligatorio de Contraseña
    if (showPasswordChangeDialog) {
        var currentPw by remember { mutableStateOf(password) }
        var newPw by remember { mutableStateOf("") }
        var confirmPw by remember { mutableStateOf("") }
        var changePwError by remember { mutableStateOf<String?>(null) }
        var isSubmittingChange by remember { mutableStateOf(false) }

        Dialog(onDismissRequest = { /* No cerrar si es obligatorio */ }) {
            TactileCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = FieldSurfaceContainerLowest
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = FieldPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cambio Obligatorio de Clave",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = FieldOnSurface
                        )
                    }

                    Text(
                        text = "Por política de seguridad de la granja, debes actualizar tu contraseña temporal:",
                        style = MaterialTheme.typography.bodySmall,
                        color = FieldOnSurfaceVariant
                    )

                    OutlinedTextField(
                        value = currentPw,
                        onValueChange = { currentPw = it },
                        label = { Text("Contraseña Actual") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newPw,
                        onValueChange = { newPw = it },
                        label = { Text("Nueva Contraseña") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = confirmPw,
                        onValueChange = { confirmPw = it },
                        label = { Text("Confirmar Nueva Contraseña") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (changePwError != null) {
                        Text(
                            text = changePwError!!,
                            color = FieldError,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            if (newPw.length < 4) {
                                changePwError = "La nueva contraseña debe tener al menos 4 caracteres."
                                return@Button
                            }
                            if (newPw != confirmPw) {
                                changePwError = "Las contraseñas no coinciden."
                                return@Button
                            }
                            isSubmittingChange = true
                            changePwError = null
                            coroutineScope.launch {
                                when (val res = apiClient.changePassword(currentPw, newPw)) {
                                    is ApiResult.Ok -> {
                                        isSubmittingChange = false
                                        showPasswordChangeDialog = false
                                        onLoginSuccess(res.value.rol, res.value.toProfile())
                                    }
                                    is ApiResult.Fail -> {
                                        isSubmittingChange = false
                                        changePwError = res.message
                                    }
                                }
                            }
                        },
                        enabled = !isSubmittingChange,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = FieldPrimary)
                    ) {
                        Text(if (isSubmittingChange) "Actualizando..." else "Confirmar y Continuar")
                    }
                }
            }
        }
    }
}
