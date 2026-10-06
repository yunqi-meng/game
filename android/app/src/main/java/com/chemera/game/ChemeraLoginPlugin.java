package com.chemera.game;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * TapTap 登录票据的壳侧出口。前端唯一调用方是 {@code shell.js#taptapTicket}。
 *
 * <p>这里只把票据交出去，不判断它是谁的：验签在服务端做（{@code AuthService.taptap} 拿票据去
 * open.tapapis.cn 换 open_id）。之所以原生侧一点都不碰身份逻辑，是因为碰了就会有两份"我以为的账号"，
 * 而其中一份在客户端——那是可以被改的。
 *
 * <p>没内置 TapSDK 时返回 code=NO_SDK 而不是抛异常：登录页据此把入口藏掉。
 * 摆一个点了必然失败的按钮，比少一个按钮更糟。
 */
@CapacitorPlugin(name = "ChemeraLogin")
public class ChemeraLoginPlugin extends Plugin {

    @PluginMethod
    public void status(PluginCall call) {
        LoginProvider p = Providers.login();
        JSObject ret = new JSObject();
        ret.put("hasProvider", p != null);
        ret.put("ready", p != null && p.isReady());
        ret.put("appIdConfigured", !BuildConfig.TAPTAP_APP_ID.isEmpty());
        // 同意状态在这里就报出来：登录页据此决定"先问同意，再谈授权"，
        // 而不是让玩家点了一个注定失败（也不该成功）的授权按钮。
        ret.put("consented", ConsentStore.agreed(getContext()));
        call.resolve(ret);
    }

    @PluginMethod
    public void getTapTapTicket(PluginCall call) {
        call.setKeepAlive(true);
        LoginProvider p = Providers.login();
        if (p == null) {
            call.setKeepAlive(false);
            call.reject("这个安装包未内置 TapSDK，请使用账号密码或游客进入", "NO_SDK");
            return;
        }
        if (getActivity() == null) {
            call.setKeepAlive(false);
            call.reject("界面尚未就绪，请稍后再试", "NO_ACTIVITY");
            return;
        }
        // 审核 5.8 的顺序在这里也成立：同意之前不 init 任何第三方 SDK，连"取一张票据"都不例外。
        // 之前这条路径只被广告侧判过，登录侧是漏的——TapBootstrap.init 会在同意之前就把 SDK 起来。
        if (!ConsentStore.agreed(getContext())) {
            call.setKeepAlive(false);
            call.reject("请先同意隐私政策与用户协议", "NO_CONSENT");
            return;
        }
        p.init(getActivity(), BuildConfig.TAPTAP_APP_ID);
        p.requestTicket(getActivity(), new LoginProvider.Callback() {
            @Override
            public void onResult(String ticket, String code, String msg) {
                call.setKeepAlive(false);
                if (ticket == null || ticket.isEmpty()) {
                    call.reject(msg == null || msg.isEmpty() ? "TapTap 授权未完成" : msg,
                        code == null || code.isEmpty() ? "EMPTY" : code);
                    return;
                }
                JSObject ret = new JSObject();
                ret.put("ticket", ticket);
                call.resolve(ret);
            }
        });
    }
}
