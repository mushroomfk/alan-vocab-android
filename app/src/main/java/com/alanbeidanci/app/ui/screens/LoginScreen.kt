package com.alanbeidanci.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.ui.AuthMode
import com.alanbeidanci.app.ui.AuthViewModel
import com.alanbeidanci.app.ui.components.AppCard

@Composable
fun LoginScreen(
    container: AppContainer,
    viewModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(container)),
) {
    val state by viewModel.uiState.collectAsState()
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(68.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(23.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("A", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            Text("Alan背单词", style = MaterialTheme.typography.headlineLarge)
            Text("账号与学习记录由你的服务器保存", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(26.dp))

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AuthModeButton("登录", state.mode == AuthMode.LOGIN, Modifier.weight(1f)) {
                            viewModel.setMode(AuthMode.LOGIN)
                        }
                        AuthModeButton("注册", state.mode == AuthMode.REGISTER, Modifier.weight(1f)) {
                            viewModel.setMode(AuthMode.REGISTER)
                        }
                    }
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = viewModel::setUsername,
                        modifier = Modifier.fillMaxWidth().testTag("auth_username"),
                        label = { Text("账号") },
                        leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        supportingText = { Text("4–32 位，以英文字母开头") },
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::setPassword,
                        modifier = Modifier.fillMaxWidth().testTag("auth_password"),
                        label = { Text("密码") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = viewModel::togglePasswordVisibility) {
                                Icon(
                                    if (state.passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (state.passwordVisible) "隐藏密码" else "显示密码",
                                )
                            }
                        },
                        visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                        supportingText = { Text("密码可使用任意非空字符") },
                    )
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = viewModel::submit,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("auth_submit"),
                        shape = RoundedCornerShape(17.dp),
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Text(if (state.mode == AuthMode.LOGIN) "安全登录" else "创建账号")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                Text(
                    "加密连接 · 开源版未配置服务器\n不使用手机号，也不会发送短信验证码",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun AuthModeButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            modifier = Modifier.padding(vertical = 13.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
