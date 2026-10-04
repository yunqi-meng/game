import { defineStore } from "pinia";
import http from "./api";

const TK = "chem-admin-token", US = "chem-admin-user", RL = "chem-admin-role", MC = "chem-admin-mustchange";

export const useAuth = defineStore("auth", {
  state: () => ({
    token: localStorage.getItem(TK) || "",
    user: localStorage.getItem(US) || "",
    role: localStorage.getItem(RL) || "",
    // 初始/被重置口令未换掉之前，服务端只放行自助改密；这里同步把界面钉在改密框上
    mustChange: localStorage.getItem(MC) === "1",
  }),
  getters: {
    logged: (s) => !!s.token,
    canWrite: (s) => s.role === "super" || s.role === "editor",
    isSuper: (s) => s.role === "super",
  },
  actions: {
    async login(user, pass) {
      const d = await http.post("/login", { user, pass });
      this.token = d.token; this.user = d.user; this.role = d.role;
      this.mustChange = !!d.mustChange;
      localStorage.setItem(TK, d.token);
      localStorage.setItem(US, d.user);
      localStorage.setItem(RL, d.role);
      localStorage.setItem(MC, this.mustChange ? "1" : "0");
    },
    /** 改密成功后一律重新登录：后台令牌是无状态 JWT，不换令牌无从撤销旧会话。 */
    async changePass(oldPass, newPass) {
      await http.post("/me/password", { old: oldPass, new: newPass });
      this.logout();
    },
    logout() {
      this.token = this.user = this.role = "";
      this.mustChange = false;
      localStorage.removeItem(TK);
      localStorage.removeItem(US);
      localStorage.removeItem(RL);
      localStorage.removeItem(MC);
    },
  },
});
