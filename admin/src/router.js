import { createRouter, createWebHashHistory } from "vue-router";
import { useAuth } from "./store";

const routes = [
  { path: "/login", component: () => import("./views/Login.vue"), meta: { public: true } },
  {
    path: "/",
    component: () => import("./views/Layout.vue"),
    children: [
      { path: "", redirect: "/dashboard" },
      { path: "dashboard", component: () => import("./views/Dashboard.vue"), meta: { title: "数据看板" } },
      { path: "content", component: () => import("./views/Content.vue"), meta: { title: "内容管理" } },
      { path: "health", component: () => import("./views/Health.vue"), meta: { title: "内容体检" } },
      { path: "config", component: () => import("./views/Config.vue"), meta: { title: "运营配置" } },
      { path: "users", component: () => import("./views/Users.vue"), meta: { title: "用户与存档" } },
      { path: "moderation", component: () => import("./views/Moderation.vue"), meta: { title: "审核与审计" } },
      { path: "admins", component: () => import("./views/Admins.vue"), meta: { title: "管理员账号", super: true } },
    ],
  },
];

const router = createRouter({ history: createWebHashHistory("/admin/"), routes });

router.beforeEach((to) => {
  const auth = useAuth();
  if (!to.meta.public && !auth.logged) return "/login";
  if (to.path === "/login" && auth.logged) return "/dashboard";
  // 服务端同样会拒（403），这里只是不让非超管看到一个必然空白页的入口
  if (to.meta.super && !auth.isSuper) return "/dashboard";
  return true;
});

export default router;
