<template>
  <div>
    <div class="bar">
      <el-input v-model="newKey" placeholder="新增配置键（如 daily_gift_coins）" style="width:240px" />
      <el-button v-if="auth.canWrite" :loading="busy('addKey')" @click="addNew">＋ 新增配置</el-button>
      <!-- 搜索是服务端做的（H6-3）：整表已经不再一次搬进浏览器，只拿当前页的话搜不到没在这一页的键 -->
      <el-input v-model="q" placeholder="按键名 / 分类 / 备注搜索" style="width:220px"
                clearable @keyup.enter="search" @clear="search" />
      <el-button :loading="busy('list')" @click="search">搜索</el-button>
      <div style="flex:1"></div>
      <span class="tip">「生效范围」与编辑器形态都由服务端引擎实际读取的那份说明书（ConfigSpec）下发：标着「仅前端」「已退役」的键，改了不会改变任何人的结算</span>
    </div>

    <el-table :data="rows" border v-loading="loading" height="calc(100vh - 200px)">
      <el-table-column prop="cfgKey" label="键" width="150" />
      <el-table-column label="名称" width="140">
        <template #default="s">
          <span v-if="specOf(s.row)">{{ specOf(s.row).zh }}</span>
          <el-tag v-else size="small" type="warning" effect="plain">未收录</el-tag>
        </template>
      </el-table-column>
      <!-- 这一列是乐观锁的版本号本身（H6-2）：看得见"谁在什么时候动的"，才看得懂保存被拒时那句话 -->
      <el-table-column label="最后改动" width="150">
        <template #default="s">
          <span :title="'改动人：' + (s.row.updatedBy || '未知')">{{ fmtTime(s.row.updatedAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="生效范围" width="104">
        <template #default="s">
          <el-tag v-if="specOf(s.row)" size="small" :type="tone(s.row).tag" :class="{ dim: tone(s.row).dim }">{{ specOf(s.row).scope }}</el-tag>
          <span v-else class="tip">—</span>
        </template>
      </el-table-column>
      <el-table-column label="作用（悬停看全文与当前值）" min-width="200">
        <template #default="s">
          <el-tooltip v-if="specOf(s.row)" placement="top" effect="dark" :show-after="120">
            <template #content>
              <div style="max-width:440px;line-height:1.7">
                <div>{{ specOf(s.row).effect }}</div>
                <div style="color:#fbbf24;margin-top:4px">{{ specOf(s.row).note }}</div>
                <div style="color:#cbd5e1;margin-top:4px">当前值：{{ fmt(s.row.cfgValue, 200) }}</div>
                <div style="color:#94a3b8;margin-top:4px">出处：{{ specOf(s.row).refs }}</div>
              </div>
            </template>
            <span class="eff">{{ headOf(specOf(s.row).effect) }}</span>
          </el-tooltip>
          <span v-else class="tip">引擎的 Content.Config 里没有这个字段：只会原样下发给前端，改了不影响服务端结算。</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="96">
        <template #default="s">
          <el-button link type="primary" @click="edit(s.row)">编辑</el-button>
          <el-button v-if="auth.canWrite" link type="danger" :loading="busy('del:' + s.row.cfgKey)" @click="del(s.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top:10px" background layout="prev, pager, next, total"
      :total="total" :page-size="size" :current-page="page" @current-change="onPage" />

    <el-dialog v-model="dlg" :title="'编辑配置 · ' + cur" width="680px" top="6vh">
      <!-- 保存前就把"改了会怎样"摆在编辑区上方，避免调崩经济却以为是下发没生效 -->
      <el-alert v-if="curSpec" :closable="false" :type="alertType" style="margin-bottom:14px">
        <template #title><b>{{ curSpec.zh }}</b> · {{ curSpec.scope }}</template>
        <div class="spec">{{ curSpec.effect }}</div>
        <div class="spec warn">{{ curSpec.note }}</div>
        <div class="spec ref">出处：{{ curSpec.refs }}</div>
      </el-alert>
      <el-alert v-else :closable="false" type="warning" style="margin-bottom:14px"
                title="未收录的配置键"
                description="服务端 Content.Config 里没有这个字段，保存后只会原样下发给前端，不会参与任何结算。" />
      <el-form label-width="90px" label-position="right">
        <el-form-item label="键"><el-input :model-value="cur" readonly /></el-form-item>
        <!-- 乐观锁（H6-2）：把"这次保存依据的是哪一版"摆在表单里，被拒时才看得懂那句"被人改过" -->
        <el-form-item label="改动依据">
          <span class="tip" v-if="stamp">
            这一版由 {{ stampBy || '未知' }} 在 {{ fmtTime(stamp) }} 改过。保存时面板会把它当版本号带回服务端：
            期间若有人动过这个键，这次写入会被拒绝并让你先载入最新，而不是被谁静默盖掉。
          </span>
          <span class="tip" v-else>
            新增键：保存时带上「这一行还不该存在」的前提。别人在你填表期间建了同名键的话，这次会被拒——
            覆盖写会把人家那份一起抹掉。
          </span>
        </el-form-item>
        <el-form-item label="分类"><el-input v-model="meta.category" placeholder="general / economy / ..." /></el-form-item>
        <el-form-item label="备注"><el-input v-model="meta.remark" placeholder="来源常量名或自定义备注（作用说明看上方提示条）" /></el-form-item>
        <el-form-item label="值">
          <div style="width:100%">

            <!-- number -->
            <el-input-number v-if="kind === 'number'" v-model="val" :step="kindMeta.step || 1"
                             :precision="kindMeta.precision" controls-position="right" />

            <!-- string list -->
            <div v-else-if="kind === 'strList'" style="width:100%">
              <div v-for="(s,i) in val" :key="i" class="row">
                <el-input v-model="val[i]" style="flex:1" />
                <el-button link type="danger" @click="val.splice(i,1)">删除</el-button>
              </div>
              <el-button link type="primary" @click="val.push('')">＋ 添加</el-button>
            </div>

            <!-- number list -->
            <div v-else-if="kind === 'numList'" style="width:100%">
              <div v-for="(s,i) in val" :key="i" class="row">
                <el-input-number v-model="val[i]" :min="0" :precision="0" controls-position="right" />
                <el-button link type="danger" @click="val.splice(i,1)">删除</el-button>
              </div>
              <el-button link type="primary" @click="val.push(0)">＋ 添加</el-button>
            </div>

            <!-- discover_bonus: {级别: [下限,上限]} -->
            <div v-else-if="kind === 'bonusMap'" style="width:100%">
              <div v-for="k in Object.keys(val)" :key="k" class="row">
                <span class="lbl">Lv.{{ k }}</span>
                <el-input-number v-model="val[k][0]" :min="0" :precision="0" controls-position="right" />
                <span class="lbl">~</span>
                <el-input-number v-model="val[k][1]" :min="0" :precision="0" controls-position="right" />
              </div>
            </div>

            <!-- quality: [{q,zh,mult}] -->
            <div v-else-if="kind === 'quality'" style="width:100%">
              <div class="hdr"><span class="c1">等级q</span><span class="c2">名称</span><span class="c3">倍率</span><span class="c1"></span></div>
              <div v-for="(it,i) in val" :key="i" class="row">
                <el-input-number v-model="it.q" :min="0" :precision="0" controls-position="right" class="c1" />
                <el-input v-model="it.zh" class="c2" />
                <el-input-number v-model="it.mult" :step="0.1" :precision="2" controls-position="right" class="c3" />
                <el-button link type="danger" class="c1" @click="val.splice(i,1)">删除</el-button>
              </div>
              <el-button link type="primary" @click="val.push({q:val.length, zh:'', mult:1})">＋ 添加品质</el-button>
            </div>

            <!-- recharge: [{c,d}] -->
            <div v-else-if="kind === 'recharge'" style="width:100%">
              <div class="hdr"><span class="c2">充值(元)</span><span class="c2">钻石</span><span class="c1"></span></div>
              <div v-for="(it,i) in val" :key="i" class="row">
                <el-input-number v-model="it.c" :min="0" :precision="0" controls-position="right" class="c2" />
                <el-input-number v-model="it.d" :min="0" :precision="0" controls-position="right" class="c2" />
                <el-button link type="danger" class="c1" @click="val.splice(i,1)">删除</el-button>
              </div>
              <el-button link type="primary" @click="val.push({c:0,d:0})">＋ 添加档位</el-button>
            </div>

            <!--
              ad: {enabled,dailyTotal,minLevel,ticketTtlSec,viewPoints,slots[],unlocks[]}
              这是本作唯一的变现面，也是引擎里唯一由人手工填的目录，所以逐行给表单而不是让人抄 JSON。
              规则一条都不在这里重写：reward/皮肤清单与数值边界都取自 /config/ad/schema（后端 AdConfigValidator 出的），
              保存被拒时把服务端原话显示在编辑区里——它比任何前端提示都更清楚"这一行为什么发不出奖"。
            -->
            <div v-else-if="kind === 'ad'" style="width:100%">
              <div class="row">
                <span class="lbl">总开关</span>
                <el-switch v-model="val.enabled" active-text="开放广告中心" inactive-text="关停" />
              </div>
              <div class="row">
                <span class="lbl">每日总次数</span><el-input-number v-model="val.dailyTotal" :min="0" :max="200" :precision="0" controls-position="right" class="c3" />
                <span class="lbl">开放等级</span><el-input-number v-model="val.minLevel" :min="1" :max="30" :precision="0" controls-position="right" class="c3" />
              </div>
              <div class="row">
                <span class="lbl">工单有效秒</span><el-input-number v-model="val.ticketTtlSec" :min="30" :max="3600" :precision="0" controls-position="right" class="c3" />
                <span class="lbl">每次积分</span><el-input-number v-model="val.viewPoints" :min="0" :max="100" :precision="0" controls-position="right" class="c3" />
              </div>
              <div class="tip">每日总次数是全局限流，各广告位的「每日」再大也超不过它；工单有效期低于 30 秒会被引擎回落成 900 秒。</div>

              <div class="sub-h">广告位（看完一段发什么）</div>
              <div v-for="(s,i) in val.slots" :key="'s'+i" class="lab">
                <div class="lab-h">
                  <b>{{ s.kind || '（未填 kind）' }}</b>
                  <el-button link type="danger" @click="val.slots.splice(i,1)">删除</el-button>
                </div>
                <div class="row">
                  <span class="lbl">kind</span><el-input v-model="s.kind" class="c1" placeholder="boom" />
                  <span class="lbl">名称</span><el-input v-model="s.zh" class="c2" />
                </div>
                <div class="row">
                  <span class="lbl">说明</span><el-input v-model="s.desc" style="flex:1" placeholder="玩家在广告位上看的一句话" />
                </div>
                <div class="row">
                  <span class="lbl">奖励</span>
                  <el-select v-model="s.reward" filterable allow-create default-first-option class="c2" placeholder="coins">
                    <el-option v-for="r in adRewards" :key="r" :label="r" :value="r" />
                  </el-select>
                  <span class="lbl">数量</span><el-input-number v-model="s.amount" :min="0" :precision="0" controls-position="right" class="c3" />
                </div>
                <div class="row">
                  <span class="lbl">每日</span><el-input-number v-model="s.daily" :min="0" :precision="0" controls-position="right" class="c3" />
                  <span class="lbl">冷却秒</span><el-input-number v-model="s.cooldownSec" :min="0" :precision="0" controls-position="right" class="c3" />
                  <span class="tip">每日填 0 即下线该位</span>
                </div>
              </div>
              <el-button link type="primary" @click="addSlot">＋ 添加广告位</el-button>

              <div class="sub-h">积分兑换（取代原钻石商店）</div>
              <div v-for="(u,i) in val.unlocks" :key="'u'+i" class="lab">
                <div class="lab-h">
                  <b>{{ u.id || '（未填 id）' }}</b>
                  <el-button link type="danger" @click="val.unlocks.splice(i,1)">删除</el-button>
                </div>
                <div class="row">
                  <span class="lbl">id</span><el-input v-model="u.id" class="c1" placeholder="skin_cyber" />
                  <span class="lbl">名称</span><el-input v-model="u.zh" class="c2" />
                </div>
                <div class="row">
                  <span class="lbl">说明</span><el-input v-model="u.desc" style="flex:1" />
                </div>
                <div class="row">
                  <span class="lbl">奖励</span>
                  <el-select v-model="u.reward" filterable allow-create default-first-option class="c2" placeholder="coins">
                    <el-option v-for="r in adRewards" :key="r" :label="r" :value="r" />
                  </el-select>
                  <span class="lbl">积分价</span><el-input-number v-model="u.cost" :min="0" :precision="0" controls-position="right" class="c3" />
                </div>
                <div class="row">
                  <span class="lbl">数量</span><el-input-number v-model="u.amount" :min="0" :precision="0" controls-position="right" class="c3" />
                  <template v-if="u.reward === 'skin'">
                    <span class="lbl">皮肤</span>
                    <el-select v-model="u.target" class="c2" placeholder="选皮肤">
                      <el-option v-for="k in adSkins" :key="k" :label="k" :value="k" />
                    </el-select>
                  </template>
                  <span class="lbl">仅一次</span><el-switch v-model="u.once" />
                </div>
              </div>
              <el-button link type="primary" @click="addUnlock">＋ 添加兑换项</el-button>
            </div>

            <!--
              curfew: {enabled,zone,days,from,to,extraDates,hint}
              这一项和 ad 的后果方向相反：填坏了不会少发钱，而是把未成年人整个关在门外，
              而且游戏里不会有任何报错回声（字段拼错=引擎静默用默认值）。所以逐字段给控件，
              边界与默认窗口都取自 /config/compliance/schema，规则一条都不在前端重写。
            -->
            <div v-else-if="kind === 'curfew'" style="width:100%">
              <div class="row">
                <span class="lbl">总开关</span>
                <el-switch v-model="val.enabled" active-text="约束未成年人" inactive-text="不约束" />
                <el-tag v-if="!val.enabled" size="small" type="warning" effect="plain">合规开关：关掉后闸门对所有账号放行</el-tag>
              </div>
              <div class="row">
                <span class="lbl">放行日</span>
                <el-checkbox-group v-model="val.days">
                  <el-checkbox v-for="d in weekdays" :key="d.v" :value="d.v">{{ d.zh }}</el-checkbox>
                </el-checkbox-group>
              </div>
              <div class="row">
                <span class="lbl">放行窗口</span>
                <el-time-picker v-model="val.from" format="HH:mm" value-format="HH:mm" placeholder="20:00" class="c3" />
                <span class="lbl">至</span>
                <el-time-picker v-model="val.to" format="HH:mm" value-format="HH:mm" placeholder="21:00" class="c3" />
              </div>
              <div class="row">
                <span class="lbl">时区</span>
                <el-input v-model="val.zone" class="c2" :placeholder="compCurfewDefault.zone" />
                <span class="tip">决定「现在几点」按哪儿算，与服务器在哪个机房无关</span>
              </div>
              <div class="tip">窗口是「当天 from 起、to 止」的左闭右开区间，只约束被标记为未成年人的账号（玩家自行开启青少年模式，或在本页【用户管理】标记）。放行日一个都不勾、又不填下面的节假日，等于永久无法游玩——保存会被服务端拦住。</div>

              <div class="sub-h">节假日补放（按年填，命中当天同样按上面的窗口放行）</div>
              <div v-for="(dt, i) in val.extraDates" :key="'d' + i" class="row">
                <el-date-picker v-model="val.extraDates[i]" type="date" value-format="YYYY-MM-DD"
                                :placeholder="'日期 ' + (i + 1)" class="c3" />
                <el-button link type="danger" @click="val.extraDates.splice(i, 1)">删除</el-button>
              </div>
              <el-button link type="primary" @click="val.extraDates.push('')">＋ 添加日期</el-button>
              <div class="tip">必须是 yyyy-MM-dd：引擎按 ISO 日期比对，写成 2026-10-1 或 10月1日 的那天永远匹配不上。</div>

              <div class="sub-h">给玩家看的那句话（跟在「距下次可玩还有…」前面）</div>
              <el-input v-model="val.hint" type="textarea" :rows="2" :maxlength="compCurfewBounds.hint"
                        show-word-limit :placeholder="compCurfewDefault.hint" />
              <div class="tip">留空即用占位符里那句内置文案，不会显示成空白。</div>
            </div>

            <!--
              app_version: {minBuild,latestBuild,note,url}
              只有安卓/iOS 壳读它，H5 不读，所以改错的后果是"老包进不来"而不是"现有玩家受影响"。
              minBuild 高于 latestBuild 会被服务端拒（那等于宣布所有人都得升级到一个不存在的版本）。
            -->
            <div v-else-if="kind === 'appVersion'" style="width:100%">
              <div class="row">
                <span class="lbl">最低可进</span>
                <el-input-number v-model="val.minBuild" :min="0" :max="compVersionBounds.build[1]" :precision="0"
                                 controls-position="right" class="c3" />
                <span class="lbl">最新版本</span>
                <el-input-number v-model="val.latestBuild" :min="0" :max="compVersionBounds.build[1]" :precision="0"
                                 controls-position="right" class="c3" />
              </div>
              <div class="tip">两个数都是安卓的 versionCode（整数）。壳启动时问一次：低于「最低可进」= 硬拦必须更新；介于两者之间 = 「建议更新」，可跳过一次。抬高「最低可进」会把旧包玩家直接挡在门外，发版前确认线上包确实都升上去了。</div>
              <div class="row"><span class="lbl">更新说明</span><el-input v-model="val.note" type="textarea" :rows="2" style="flex:1" placeholder="玩家在更新弹窗里看到的正文" /></div>
              <div class="row"><span class="lbl">下载地址</span><el-input v-model="val.url" style="flex:1" placeholder="https://（留空表示不引导下载）" /></div>
              <div class="tip">地址必须是 http(s) 开头：壳里用系统浏览器打开它，填一句中文只会让玩家点完没反应。</div>
            </div>

            <!--
              analytics_enabled: true / false（标量键）
              合规面的开关：关掉之后服务端不再把行为埋点写库，但接口照旧回 200。
              只认布尔是后端 ComplianceConfigValidator 定的口径（填 0 / "false" 会被静默 coerce 成关停）。
            -->
            <div v-else-if="kind === 'flag'" style="width:100%">
              <div class="row">
                <span class="lbl">采集</span>
                <el-switch v-model="val" active-text="写入行为记录" inactive-text="停止采集" />
                <el-tag v-if="val === false" size="small" type="warning" effect="plain">关停中：看板从这一刻起不再增长</el-tag>
              </div>
              <div class="tip">这不是省流量或省磁盘的开关：它是隐私政策里「你可以要求停止收集行为数据」的兑现点，拨下去等于对外承诺停止采集。历史数据不会被删除（要清理得单独走 DB），玩家端也不会看到任何错误——服务端只是不再入库。</div>
            </div>

            <!-- lab_upgrades: {key:{zh,desc,step,baseCost,growth,max}} -->
            <div v-else-if="kind === 'labMap'" style="width:100%">
              <div v-for="k in Object.keys(val)" :key="k" class="lab">
                <div class="lab-h">
                  <b>{{ k }}</b>
                  <el-button link type="danger" @click="delete val[k]">删除</el-button>
                </div>
                <div class="row"><span class="lbl">名称</span><el-input v-model="val[k].zh" style="flex:1" /></div>
                <div class="row"><span class="lbl">说明</span><el-input v-model="val[k].desc" style="flex:1" /></div>
                <div class="row">
                  <span class="lbl">step</span><el-input-number v-model="val[k].step" :precision="0" controls-position="right" />
                  <span class="lbl">baseCost</span><el-input-number v-model="val[k].baseCost" :precision="0" controls-position="right" />
                </div>
                <div class="row">
                  <span class="lbl">growth</span><el-input-number v-model="val[k].growth" :step="0.1" :precision="2" controls-position="right" />
                  <span class="lbl">max</span><el-input-number v-model="val[k].max" :precision="0" controls-position="right" />
                </div>
              </div>
              <el-button link type="primary" @click="addLab">＋ 添加升级项</el-button>
            </div>

            <!-- 兜底：JSON -->
            <el-input v-else v-model="jsonText" type="textarea" :rows="8" style="font-family:monospace" />
          </div>
        </el-form-item>
        <!-- 服务端拒了就把它原话摊开：一处写错常常连带几行都有毛病，toast 根本读不完 -->
        <el-alert v-if="saveErr.length" :closable="false" type="error">
          <template #title><b>保存被拒（{{ saveErr.length }} 处）</b></template>
          <div v-for="(e, i) in saveErr" :key="i" class="spec">· {{ e }}</div>
        </el-alert>
      </el-form>

      <template #footer>
        <el-button @click="dlg=false">取消</el-button>
        <el-button v-if="auth.canWrite" type="primary" :loading="busy('save')" @click="save">保存并下发</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import http, { isConflict } from "../api";
import { useBusy } from "../busy";
import { askReloadOnConflict, explainConflict } from "../conflict";
import { useAuth } from "../store";

const auth = useAuth();
/* 防连点（H6-4）：配置键的保存是覆盖写，第二次点会把第一次那次也重放一遍——按动作 + 键名记在途。 */
const { busy, run } = useBusy();
const rows = ref([]);
const loading = ref(false);
const newKey = ref("");
const specs = ref([]);          // 后端 ConfigSpec：作用/生效范围/风险/代码出处
// 分页与搜索（H6-3）：这一张表以前是整表回传，面板拿着全部行在前端筛。
// 现在整表不再进浏览器，所以搜索框改成服务端筛，"同名键是否已存在"也不能再看手上的这几行。
const q = ref("");
const total = ref(0);
const page = ref(1);
const size = 20;

function specOf(row) { return specs.value.find(s => s.key === row.cfgKey) || null; }
/**
 * 色阶与编辑器形态都由后端 ConfigSpec 下发（H6）。
 *
 * 这里原来有两张表：`SCOPE_TYPE`（scope 中文 → 颜色）和 `KIND`（键名 → 编辑器形态 + 步进精度）。
 * 后端加了「已退役」这一档说明，这两张表却没人记得跟着加，于是 recharge 一路显示成温和的灰色，
 * 运营完全可能以为改了它还能收到钱。本地 grep 看不出这种错位——少了的那一行本来就在那张表之外。
 * 所以两张表都删掉，只留一份映射：**颜色跟 scope 走、编辑器跟值的样子走，都由服务端说**。
 */
const TONE = {
  danger: { tag: "danger", alert: "error" },        // 动全体玩家结算
  warning: { tag: "warning", alert: "warning" },    // 前后端共用 / 只有一部分字段生效
  guard: { tag: "primary", alert: "warning" },      // 服务端行为：不动钱，动的是合规与放行
  info: { tag: "info", alert: "info" },             // 仅前端
  retired: { tag: "info", alert: "info", dim: true } // 已退役：改了什么都不发生
};
// 后端新增一档而这里没跟上时，宁可显眼（warning）也不要安静地灰掉——那正是上一轮的错法。
const TONE_FALLBACK = { tag: "warning", alert: "warning" };
function tone(row) { return TONE[specOf(row)?.tone] || TONE_FALLBACK; }
/** form 形如 "number:0.1:2" 或 "ad"：冒号前是编辑器，后面是步进与小数位（同样只有后端一份答案）。 */
function formOf(row) { return specOf(row)?.form || "json"; }
function formKind(form) { return String(form || "json").split(":")[0]; }

function headOf(text) { return text.length > 26 ? text.slice(0, 26) + "…" : text; }

const LAB_DEFAULT = { zh: "", desc: "", step: 1, baseCost: 1000, growth: 1.6, max: 5 };
// 新增行只为给表单一个可编辑的空壳，数值语义由后端 AdConfigValidator 判
const SLOT_DEFAULT = { kind: "", zh: "", desc: "", reward: "coins", amount: 100, daily: 1, cooldownSec: 0 };
const UNLOCK_DEFAULT = { id: "", zh: "", desc: "", cost: 10, reward: "coins", amount: 1, target: null, once: true };

const dlg = ref(false);
const cur = ref("");
/**
 * 乐观锁的版本号（H6-2）：列表那一行的 updatedAt 原文，由库的 ON UPDATE CURRENT_TIMESTAMP(3) 盖章。
 * 打开对话框时记下，保存时原样带回 expect；空串表示"新增，这个键还不该存在"。
 */
const stamp = ref("");
const stampBy = ref("");
const val = ref(null);         // 类型化模型
const jsonText = ref("");      // 兜底 JSON
const meta = ref({ category: "general", remark: "" });
const adSchema = ref({ rewards: [], skins: [], defaults: {} });  // 由后端出，前端不抄第二份奖励清单
const compSchema = ref({ curfew: { defaults: {}, bounds: {} }, app_version: { defaults: {}, bounds: {} } });
const saveErr = ref([]);       // 保存被拒时服务端原话，摊在编辑区里

const curSpec = computed(() => specs.value.find(s => s.key === cur.value) || null);
const kind = computed(() => formKind(curSpec.value?.form));
const kindMeta = computed(() => {
  const a = String(curSpec.value?.form || "").split(":").slice(1);
  const step = Number(a[0]), prec = Number(a[1]);
  return {
    step: Number.isFinite(step) ? step : 1,
    precision: Number.isFinite(prec) ? prec : undefined
  };
});
const alertType = computed(() => (TONE[curSpec.value?.tone] || TONE_FALLBACK).alert);
const adRewards = computed(() => adSchema.value.rewards || []);
const adSkins = computed(() => adSchema.value.skins || []);
const compCurfewDefault = computed(() => (compSchema.value.curfew || {}).defaults || {});
const compCurfewBounds = computed(() => (compSchema.value.curfew || {}).bounds || {});
const compVersionDefault = computed(() => (compSchema.value.app_version || {}).defaults || {});
const compVersionBounds = computed(() => ({ build: [0, 100000], ...(compSchema.value.app_version || {}).bounds || {} }));
/** 周几的中文名只是标签；编号口径（ISO：一=1……日=7）取自后端 bounds，前端不自己认定范围。 */
const weekdays = computed(() => {
  const b = compCurfewBounds.value.days || [1, 7];
  const zh = ["", "周一", "周二", "周三", "周四", "周五", "周六", "周日"];
  const out = [];
  for (let v = b[0]; v <= b[1]; v++) out.push({ v, zh: zh[v] || "第" + v + "天" });
  return out;
});

onMounted(() => { load(); loadSpec(); loadAdSchema(); loadCompSchema(); });
async function loadSpec() { specs.value = await http.get("/config/spec"); }
async function loadAdSchema() {
  try { adSchema.value = await http.get("/config/ad/schema"); }
  catch (e) { /* 后端还没这端点时下拉留空，编辑照常（allow-create），保存仍由服务端把关 */ }
}
async function loadCompSchema() {
  try { compSchema.value = await http.get("/config/compliance/schema"); }
  catch (e) { /* 同上：拿不到说明书时占位符留空，边界判断依旧只在服务端 */ }
}
async function load() {
  await run("list", async () => {
    loading.value = true;
    try {
      const d = await http.get("/config", { params: { q: q.value, size, off: (page.value - 1) * size } });
      rows.value = d.rows; total.value = d.total;
    } finally { loading.value = false; }
  });
}
/** 换了搜索词就回第 1 页：停在第 4 页筛一个只有两行结果的关键词，面板会显示一张空表。 */
function search() { page.value = 1; load(); }
function onPage(p) { page.value = p; load(); }

/**
 * 按键名精确查一行（H6-3）：列表只剩当前这一页之后，"这个键在不在库里"就问了个
 * 手上没有的问题。库里那份才是答案，所以这里走服务端搜索再逐字比键名。
 *
 * <p>它只是<b>提前告知</b>，不是判据：真正的拦截是保存时那句 {@code expect=none} 撞上已有键
 * 返回的 409（先查后写之间仍可能有人建出来）。查询失败按"不存在"处理——拦人的是那道 409，
 * 不该因为搜索本身出错就让人连新键都开不了。
 */
async function findRow(key) {
  try {
    // size 取服务端认的最大页长（Page.MAX_SIZE）：LIKE 命中的是"包含"关系，
    // 只拿一页的话精确同名那条可能被别的相似键挤出去，预检就成了假的"不存在"。
    const d = await http.get("/config", { params: { q: key, size: 200, off: 0 } });
    return (d.rows || []).find(r => r.cfgKey === key) || null;
  } catch (e) { return null; }
}
function fmt(raw, max = 120) {
  try { const s = JSON.stringify(JSON.parse(raw)); return s.length > max ? s.slice(0, max) + "…" : s; }
  catch (e) { return raw; }
}
/** 时间戳给运营看的形状；回传给 expect 的仍是 ISO 原文，别把这两个混成一个。 */
function fmtTime(t) { return t ? String(t).replace("T", " ").slice(0, 19) : "—"; }

function edit(row) {
  cur.value = row.cfgKey;
  saveErr.value = [];
  // 版本号抄的是列表这一行，也就是运营眼前看到的那一版：中间有人动过就会被服务端拒
  stamp.value = row.updatedAt || "";
  stampBy.value = row.updatedBy || "";
  meta.value = { category: row.category || "general", remark: row.remark || "" };
  let parsed;
  try { parsed = JSON.parse(row.cfgValue); } catch (e) { parsed = row.cfgValue; }
  const fk = formKind(formOf(row));
  if (fk !== "json") {
    let model = (typeof parsed === "object" && parsed !== null)
      ? (Array.isArray(parsed) ? parsed.map(o => ({ ...o })) : cloneDeep(parsed))
      : parsed;
    // 广告目录拿不到内置默认时（老后端/接口失败）宁可退回 JSON 文本编辑，
    // 也不要显示一个空目录——保存下去就把"缺省=用内置六行"变成了"清空"。
    if (fk === "ad") {
      model = expandAd(model);
      if (!model) { jsonText.value = JSON.stringify(parsed, null, 2); val.value = null; dlg.value = true; return; }
    }
    if (fk === "curfew") model = expandCurfew(model);
    if (fk === "appVersion") model = expandAppVersion(model);
    // 标量布尔键：显示的是引擎真正的读法（只有显式 false 才算关），不是把库里的原始值原样回显
    if (fk === "flag") model = !(parsed === false || parsed === "false" || parsed === 0);
    val.value = model;
    jsonText.value = "";
  } else {
    jsonText.value = JSON.stringify(parsed, null, 2);
    val.value = null;
  }
  dlg.value = true;
}

/** slots/unlocks 缺省与空数组在引擎里语义不同，进表单前把真正生效的那份展开成可编辑的行。 */
function expandAd(o) {
  const src = (o && typeof o === "object" && !Array.isArray(o)) ? o : {};
  const d = adSchema.value.defaults || {};
  const out = { ...src };
  if (typeof out.enabled !== "boolean") out.enabled = out.enabled === undefined ? true : !!out.enabled;
  if (!Array.isArray(out.slots)) {
    if (!Array.isArray(d.slots)) return null;
    out.slots = d.slots.map(s => ({ ...s }));
  } else out.slots = out.slots.map(s => ({ ...s }));
  if (!Array.isArray(out.unlocks)) {
    if (!Array.isArray(d.unlocks)) return null;
    out.unlocks = d.unlocks.map(u => ({ ...u }));
  } else out.unlocks = out.unlocks.map(u => ({ ...u }));
  return out;
}

/**
 * curfew 的"缺字段"在引擎里各有一个兜底值（开关默认开、时区默认上海、窗口默认 20:00-21:00、
 * 放行日默认周五六日），表单先把兜底展开成看得见的值——否则运营面对的是空框，
 * 保存下去却不知道自己实际定了什么窗口。
 *
 * <p>唯一不能替它编的是 days：显式的空数组是"哪一天都不放行"这个有意收紧的动作，
 * 与"字段缺失"在引擎里语义不同，所以只有缺字段才展开成默认五六日。
 * hint 也留空而不是填进默认句子：留空=继续跟随内置文案，写死就等于从此不再同步。
 */
function expandCurfew(o) {
  const src = (o && typeof o === "object" && !Array.isArray(o)) ? o : {};
  const d = compCurfewDefault.value || {};
  const out = { ...src };
  if (typeof out.enabled !== "boolean") out.enabled = out.enabled === undefined ? true : !!out.enabled;
  out.days = Array.isArray(out.days) ? [...out.days] : (Array.isArray(d.days) ? [...d.days] : []);
  out.extraDates = Array.isArray(out.extraDates) ? [...out.extraDates] : [];
  if (!out.zone) out.zone = d.zone || "Asia/Shanghai";
  if (!out.from) out.from = d.from || "20:00";
  if (!out.to) out.to = d.to || "21:00";
  if (typeof out.hint !== "string") out.hint = "";
  return out;
}

/** 版本门同理：build 号缺省按 1，latestBuild 拿不到默认时给到 minBuild，避免一进表单就是非法组合。 */
function expandAppVersion(o) {
  const src = (o && typeof o === "object" && !Array.isArray(o)) ? o : {};
  const d = compVersionDefault.value || {};
  const out = { ...src };
  if (!Number.isInteger(out.minBuild)) out.minBuild = Number.isInteger(d.minBuild) ? d.minBuild : 1;
  if (!Number.isInteger(out.latestBuild)) out.latestBuild = Number.isInteger(d.latestBuild) ? d.latestBuild : out.minBuild;
  if (typeof out.note !== "string") out.note = "";
  if (typeof out.url !== "string") out.url = "";
  return out;
}
function cloneDeep(o) {
  if (Array.isArray(o)) return o.map(cloneDeep);
  if (o && typeof o === "object") { const r = {}; for (const k in o) r[k] = cloneDeep(o[k]); return r; }
  return o;
}
async function addNew() {
  if (!newKey.value) return ElMessage.error("请输入键名");
  await run("addKey", async () => {
    // 同键 PUT 是覆盖：新增框只该用来开新键，已有键请回到那一行编辑，
    // 否则一次手滑就把线上值和备注一起换掉了。
    const hit = await findRow(newKey.value);
    if (hit)
      return ElMessage.warning(`${newKey.value} 已经在库里，请直接编辑那一行（新增同名会覆盖它当前的值）`);
    cur.value = newKey.value;
    saveErr.value = [];
    stamp.value = ""; stampBy.value = "";   // 新增：没有"上一版"，保存时走「这行还不该存在」
    meta.value = { category: "general", remark: "" };
    jsonText.value = "0"; val.value = null;
    dlg.value = true;
  });
}
function addLab() {
  let key = "custom"; let i = 1;
  while (val.value && val.value[key]) key = "custom" + (++i);
  val.value = { ...val.value, [key]: { ...LAB_DEFAULT } };
}
function addSlot() { val.value.slots.push({ ...SLOT_DEFAULT }); }
function addUnlock() { val.value.unlocks.push({ ...UNLOCK_DEFAULT }); }

function currentValue() {
  if (kind.value === "json") {
    try { return JSON.parse(jsonText.value || "null"); }
    catch (e) { throw new Error("JSON 解析失败：" + e.message); }
  }
  return val.value;
}

async function save() {
  let v;
  try { v = currentValue(); } catch (e) { return ElMessage.error(e.message); }
  saveErr.value = [];
  await run("save", async () => {
    try {
      await http.put("/config",
        { key: cur.value, value: v, category: meta.value.category || "general", remark: meta.value.remark || null },
        // expect：手上这一版的版本号（空串=新增，服务端按「这个键还不该存在」判）
        { params: { expect: stamp.value || "none" } });
    } catch (e) {
      // 冲突不是"值填坏了"，摊进 saveErr 只会让人以为改改数字就能存——它要的是另一条出路
      if (isConflict(e)) {
        if (await askReloadOnConflict(e, stamp.value
          ? "「载入最新」会把你这份没保存的改动换成别人那一版；想留着它就先别关这个弹窗。"
          : "这个键在你填表期间被别人建出来了。选「载入最新」会打开他那一行让你接着改，你原来那份不会落库。"))
          await reopenWith(cur.value);
        return;
      }
      // 类型化表单（广告目录 / 合规 / 版本门）一处写错常常连带好几行都有毛病，toast 装不下；
      // 把服务端校验器的原话按条摊开显示在表单里——它比任何前端提示都更清楚这一行为什么不能存。
      // 兜底 JSON 编辑保持只有 toast：那是人手工写的自由文本，报错本来就只有一句。
      if (kind.value !== "json")
        saveErr.value = String((e && e.msg) || "保存失败").split("；").filter(Boolean);
      return;
    }
    ElMessage.success("已保存并下发"); dlg.value = false; newKey.value = "";
    // 新建的键按 category,cfg_key 排在它该在的那一页；留着上一次的搜索词，人就会以为没建出来。
    if (!stamp.value) { q.value = ""; page.value = 1; }
    load();
  });
}

/** 冲突之后唯一有用的动作：拿当前生效那一版重开表单（不拿旧草稿去覆盖）。 */
async function reopenWith(key) {
  await load();
  // 键未必在这一页（甚至不在当前搜索词的结果里），所以照旧向库里问，而不是在手上这几行找
  const row = await findRow(key);
  if (!row) {
    dlg.value = false;
    ElMessage.info(`${key} 已经不在列表里了（可能刚被删除），请确认它还要不要重建`);
    return;
  }
  edit(row);
}

async function del(row) {
  await run("del:" + row.cfgKey, async () => {
    await ElMessageBox.confirm(`删除配置 ${row.cfgKey}？删除后客户端将回退到代码内置默认值。`, "提示", { type: "warning" });
    try {
      await http.delete("/config", { params: { key: row.cfgKey, expect: row.updatedAt || "none" } });
      ElMessage.success("已删除");
    } catch (e) {
      await explainConflict(e);   // 删除没有草稿要保：说清是谁改的，刷回真相
    }
    // 删掉的是本页最后一条时退一页，否则会停在一张开着的空表上（总数由服务端给，页码不会自己退）
    if (rows.value.length === 1 && page.value > 1) page.value--;
    load();
  });
}
</script>

<style scoped>
.bar { display:flex; align-items:center; gap:8px; margin-bottom:10px; }
.tip { color:var(--el-text-color-secondary); font-size:12px; }
.eff { display:inline-block; max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:#334155; cursor:default; border-bottom:1px dotted #cbd5e1; }
/* 已退役的键：值还在库里（不能改迁移），但改它什么都不发生——标签要看着就"旧"一点。 */
.el-tag.dim { opacity:.62; text-decoration:line-through; }
.spec { font-size:12px; line-height:1.7; font-weight:400; }
.spec.warn { color:#b45309; }
.spec.ref { color:#94a3b8; font-family:ui-monospace,Consolas,monospace; }
.row { display:flex; align-items:center; gap:6px; margin-bottom:6px; }
.hdr { display:flex; gap:6px; font-size:12px; color:var(--el-text-color-secondary); margin-bottom:4px; }
.lbl { min-width:56px; color:var(--el-text-color-secondary); font-size:13px; }
.c1 { width:110px; flex:none; } .c2 { flex:1; } .c3 { width:120px; flex:none; }
.lab { border:1px dashed var(--el-border-color); border-radius:6px; padding:8px 10px; margin-bottom:8px; }
.lab-h { display:flex; justify-content:space-between; margin-bottom:6px; }
.sub-h { margin:14px 0 6px; font-weight:600; font-size:13px; color:var(--el-text-color-primary); }
</style>
