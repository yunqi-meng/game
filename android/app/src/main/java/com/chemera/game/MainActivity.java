package com.chemera.game;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;

/**
 * 唯一的 Activity：注册自研插件、接管返回键、把 WebView 调成"手机竖屏游戏"该有的样子。
 *
 * <p>这里刻意**不做**任何玩法/结算相关判断。返回键是个例外，因为它天然属于原生：
 * 它消化与否由前端回答（{@code window.ChemeraBack()}），前端只汇报"这一层界面我关掉了"，
 * 关的是什么、进度存没存都在服务端那条链路上，跟这里无关。
 */
public class MainActivity extends BridgeActivity {

    private static final String TAG = "chemera";
    /** 与 index.html 的 theme-color / SplashScreen 背景同色，避免首屏白闪。 */
    private static final int BACKDROP = Color.parseColor("#0f2c4d");
    /** 返回键问前端要答案的等待上限：超时按"前端没接住"处理，让玩家能退出去，而不被卡死。 */
    private static final long BACK_ASK_TIMEOUT_MS = 400L;
    /** 双击退出的间隔：与主流国产应用一致，长按/单击都不该把游戏关掉。 */
    private static final long DOUBLE_BACK_MS = 2000L;

    private Bridge chemeraBridge;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastBackAt = 0L;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 必须在 super.onCreate 之前：BridgeActivity 用字段初始化的 bridgeBuilder 收集插件，
        // super 里就 create() 完了，晚注册等于没注册。
        registerPlugin(ChemeraAdPlugin.class);
        registerPlugin(ChemeraLoginPlugin.class);
        registerPlugin(ChemeraInfoPlugin.class);
        super.onCreate(savedInstanceState);

        // release 包里由 capacitor.config.json 决定（默认关，见 capacitor.config.ts 的 CHEMERA_WEB_DEBUG），
        // debug 包无论如何都开：调试一个只在真机上复现的问题时，最怕的是"得先重新打包才能看日志"。
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);

        chemeraBridge = getBridge();
        tuneWebView();
        takeOverBackButton();
    }

    /** WebView 的三处与"手机 H5 游戏"直接相关的设定。 */
    private void tuneWebView() {
        WebView web = chemeraBridge == null ? null : chemeraBridge.getWebView();
        if (web == null) return;
        web.setBackgroundColor(BACKDROP);
        // 双指缩放要关掉：这是竖屏满屏游戏，缩放一次就把布局永久错位，玩家只会觉得"界面坏了"。
        web.getSettings().setSupportZoom(false);
        web.getSettings().setBuiltInZoomControls(false);
        web.getSettings().setDisplayZoomControls(false);
        // 音频自动播放策略保持 Capacitor 的默认（允许），sfx.js 本来就是懒创建 + 首次手势 resume()，
        // 强行改成"必须用户手势"反而会让切后台回来的 BGM 起不来。
    }

    /**
     * 返回键交给前端逐级消化：弹窗 → 教程 → 回实验台 → 复位搜索/分段 → 双击退出。
     *
     * <p>为什么前端说了算：这四层状态的先后顺序只有界面自己知道，原生去猜 DOM 可见性
     * 等于把 UI 逻辑抄第二份，两份迟早不一致（而这正是"返回键行为诡异"的由来）。
     *
     * <p>为什么要设超时：JS 主线程卡住时 evaluateJavascript 的回调可以永远不来，
     * 那时如果坚持等答案，玩家连"退出"都按不出来。
     */
    private void takeOverBackButton() {
        final OnBackPressedDispatcher dispatcher = getOnBackPressedDispatcher();
        final OnBackPressedCallback[] holder = new OnBackPressedCallback[1];
        holder[0] = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                askFrontendThenMaybeExit(dispatcher, holder[0]);
            }
        };
        dispatcher.addCallback(this, holder[0]);
    }

    private void askFrontendThenMaybeExit(final OnBackPressedDispatcher dispatcher, final OnBackPressedCallback self) {
        final boolean[] answered = new boolean[] { false };
        Runnable timeout = new Runnable() {
            @Override
            public void run() {
                if (answered[0]) return;
                answered[0] = true;
                Log.w(TAG, "返回键等前端答案超时，按未消化处理");
                nativeBack(dispatcher, self);
            }
        };
        ui.postDelayed(timeout, BACK_ASK_TIMEOUT_MS);

        chemeraBridge.eval("window.ChemeraBack ? (window.ChemeraBack() === true ? 1 : 0) : 0",
            new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (answered[0]) return;                       // 超时那一路已经处理过了
                    answered[0] = true;
                    ui.removeCallbacks(timeout);
                    boolean consumed = value != null && value.replace("\"", "").trim().equals("1");
                    if (!consumed) nativeBack(dispatcher, self);
                }
            });
    }

    /** 前端没接住：先提示"再按一次退出"，两次之间才真的关应用。 */
    private void nativeBack(final OnBackPressedDispatcher dispatcher, OnBackPressedCallback self) {
        long now = System.currentTimeMillis();
        if (now - lastBackAt < DOUBLE_BACK_MS) {
            lastBackAt = 0L;
            self.setEnabled(false);
            dispatcher.onBackPressed();                             // 交回系统默认行为（结束 Activity）
            self.setEnabled(true);
            return;
        }
        lastBackAt = now;
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, "再按一次返回键退出游戏", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
