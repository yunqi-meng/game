import type { CapacitorConfig } from '@capacitor/cli';

/**
 * 化学实验室：元素纪元 —— 安卓壳配置。
 *
 * appId 一旦在 TapTap 建档就不能改（改名可以，改包名等于换一个应用），所以它和 keystore
 * 一样属于"发出去就收不回来"的东西，动它之前先确认后台登记的是哪一个。
 *
 * webDir 直接指向仓库里的 frontend/：本作是零构建的 vanilla JS，没有打包这一步，
 * Capacitor 只做"把整个目录塞进 assets/public 并用 WebView 打开"。
 *
 * Android 的 WebView 在壳里用 https://localhost 作为 origin —— 前端 cloud.js 判可用性用的
 * 就是 /^https?:/，因此不需要为它开特殊通道；真正需要记得的是 API 地址：见 js/app-config.js，
 * 由 app/app-config.gradle 在构建期生成，绝不允许把 https://localhost:8080 这种开发地址带进发布包。
 */

/**
 * 只有"调试开关"这类不改内容就不能构建的选项才从环境变量读。
 * 密钥类一律不进这个文件：它会随仓库提交，而 mediaKey / keystore 口令的归宿和
 * server/.env 一样，只在构建机上以环境变量存在（gradle 侧读，见 app/build.gradle）。
 */
function env(key: string, dflt = ''): string {
  const v = process.env[key];
  return v === undefined || v === null ? dflt : String(v);
}

const config: CapacitorConfig = {
  appId: 'com.chemera.game',
  appName: '化学实验室：元素纪元',
  webDir: '../frontend',
  bundledWebRuntime: false,
  server: {
    // 不配 hostname：默认就是 https://localhost，前端据此判断"同源请求"。
    // 只有真要连本地 dev server 调试时才临时写 url，且绝不能提交。
    androidScheme: 'https',
  },
  plugins: {
    // 首屏由原生 WebView 渲染，白底会在切页时闪一下；跟皮肤主色保持一致由壳里 setBackgroundColor 处理。
    SplashScreen: {
      launchShowDuration: 900,
      launchAutoHide: true,
      backgroundColor: '#0f2c4d',
      androidScaleType: 'CENTER_CROP',
      showSpinner: false,
      splashFullScreen: true,
      splashImmersive: true,
    },
    Keyboard: {
      // 登录框在软键盘弹出时不能被顶出屏幕：保持 resize，跟 H5 端的 100dvh 布局一致。
      resize: 'body',
      resizeOnFullScreen: true,
    },
  },
  android: {
    // 这一份配置被当成 gradle 工程根：Capacitor 默认把原生工程放在 <配置目录>/android，
    // 那样 android/ 里会套一层 android/android/，与方案 6.1 的目录约定冲突，
    // 也和 frontend/ server/ admin/ 三个平级应用不一致。path='.' 让 npm 根 = gradle 根。
    path: '.',
    allowMixedContent: false,
    // 输入框在部分定制 ROM 上会吞掉软键盘事件，保持捕获。
    captureInput: true,
    // 默认关闭：release 包里开着它等于把整个游戏的运行时交给任何一根 USB 线
    // （chrome://inspect 能直接读出 localStorage 里的令牌）。本地联调显式开：
    //   CHEMERA_WEB_DEBUG=1 npx cap sync android
    webContentsDebuggingEnabled: env('CHEMERA_WEB_DEBUG') === '1',
  },
};

export default config;
