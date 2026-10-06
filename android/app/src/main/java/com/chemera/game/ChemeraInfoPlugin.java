package com.chemera.game;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 壳的"我是谁"：包版本号与退出。前端调用方 {@code shell.js#appBuild / #exitApp}。
 *
 * <p>{@code getBuild} 交出的就是 {@code BuildConfig.VERSION_CODE}，也就是 gradle 里那个数、
 * TapTap 后台要求递增的那个数、服务端 {@code /api/app/version} 里 minBuild 比的那个数——
 * 三者必须是同一个，所以它只能来自构建产物，绝不允许前端自己写一个数字来"声称版本"：
 * 版本门是用来硬拒老包的，一个客户端可改的字段挡不住任何人。
 */
@CapacitorPlugin(name = "ChemeraInfo")
public class ChemeraInfoPlugin extends Plugin {

    @PluginMethod
    public void getBuild(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("build", BuildConfig.VERSION_CODE);
        ret.put("versionName", BuildConfig.VERSION_NAME);
        ret.put("adSdkBundled", BuildConfig.AD_SDK_BUNDLED);
        call.resolve(ret);
    }

    /** 结束应用。前端拿到 true 就不再自己 history.back()，避免"退出后停在空白页"。 */
    @PluginMethod
    public void exit(PluginCall call) {
        if (getActivity() == null) {
            // reject 是 void，插件方法里不能写成 `return call.reject(...)`。
            call.reject("界面尚未就绪", "NO_ACTIVITY");
            return;
        }
        call.resolve();
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // finishAndRemoveTask 而不是 finish()：只 finish 会把任务留在最近任务列表里，
                // 玩家再次点开时 WebView 带着旧会话复活，那正是"退出没退干净"的现场。
                if (getActivity() != null) getActivity().finishAndRemoveTask();
            }
        });
    }
}
