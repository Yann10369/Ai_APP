package com.ai_photo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.ai_photo.auth.Session;
import com.ai_photo.net.ApiService;
import com.ai_photo.net.Models.AuthResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 登录页：调 /api/v1/auth/login。
 *
 * 流程：
 *  1) 已登录则直接跳 MainActivity（避免重复登录）
 *  2) 输入非空校验
 *  3) 子线程调 ApiService.login → 成功后 Session.save + 跳 MainActivity
 *  4) 按钮点击期间禁用，避免重复提交
 */
public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "AiPhoto.UI/Login";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText account, password;
    private Button loginBtn;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login);

        applyInsets();

        // 已登录：直接进主页，不让用户再点一次
        if (Session.isLoggedIn()) {
            goToMain();
            return;
        }

        account = findViewById(R.id.loginAccount);
        password = findViewById(R.id.loginPassword);
        loginBtn = findViewById(R.id.loginBtn);

        if (loginBtn != null) loginBtn.setOnClickListener(v -> {
            Log.d(TAG, "[UI/Login] click loginBtn");
            doLogin();
        });
        if (password != null) password.setOnEditorActionListener((v, id, ev) -> {
            Log.d(TAG, "[UI/Login] IME action on password field");
            doLogin();
            return true;
        });
    }

    private void applyInsets() {
        View scrollRoot = findViewById(android.R.id.content);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scrollRoot, (v, insets) -> {
            androidx.core.graphics.Insets systemBars =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingStart(), systemBars.top, v.getPaddingEnd(), systemBars.bottom);
            return insets;
        });
    }

    private void doLogin() {
        if (account == null || password == null || loginBtn == null) return;
        String acc = account.getText() == null ? "" : account.getText().toString().trim();
        String pwd = password.getText() == null ? "" : password.getText().toString().trim();
        if (acc.isEmpty() || pwd.isEmpty()) {
            Log.w(TAG, "[UI/Login] doLogin blocked: empty acc/pwd");
            Toast.makeText(this, "请输入账号和密码", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "[UI/Login] doLogin start acc=" + acc);
        setLoading(true);
        io.execute(() -> {
            AuthResult r = null;
            String errMsg = null;
            try {
                r = ApiService.login(acc, pwd);
            } catch (Exception e) {
                errMsg = e.getMessage();
            }
            final AuthResult finalR = r;
            final String finalErr = errMsg;
            main.post(() -> {
                setLoading(false);
                if (finalR != null && finalR.token != null) {
                    Log.d(TAG, "[UI/Login] login OK userId=" + finalR.userId);
                    Session.save(LoginActivity.this,
                            finalR.token,
                            finalR.userId,
                            finalR.username == null ? acc : finalR.username);
                    Toast.makeText(LoginActivity.this,
                            "欢迎回来 " + (finalR.username == null ? acc : finalR.username),
                            Toast.LENGTH_SHORT).show();
                    goToMain();
                } else {
                    String msg = finalErr == null ? "登录失败" : finalErr;
                    Log.w(TAG, "[UI/Login] login FAIL msg=" + msg);
                    // 把后端的 "用户名或密码错误" 之类文案原样显示
                    Toast.makeText(LoginActivity.this, msg, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void setLoading(boolean loading) {
        if (loginBtn == null) return;
        loginBtn.setEnabled(!loading);
        loginBtn.setAlpha(loading ? 0.5f : 1f);
        if (account != null) account.setEnabled(!loading);
        if (password != null) password.setEnabled(!loading);
    }

    private void goToMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }
}
