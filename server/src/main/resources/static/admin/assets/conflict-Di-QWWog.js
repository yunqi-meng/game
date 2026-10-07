import{b as a}from"./el-select-hLgKxLM8.js";import{aW as e,aX as o}from"./index-BVJ8buIi.js";async function l(t,n){if(!e(t))return!1;const c=o(t)+(n?`

`+n:"");try{return await a.confirm(c,"这一项被人改过了",{type:"warning",confirmButtonText:"载入最新",cancelButtonText:"先不覆盖",distinguishCancelAndClose:!0,customClass:"conflict-box"}),!0}catch{return!1}}async function f(t){return e(t)?(await a.alert(o(t)+`

列表已刷新，请在最新版本上再决定一次。`,"这一项被人改过了",{type:"warning",confirmButtonText:"知道了"}).catch(()=>{}),!0):!1}export{l as a,f as e};
