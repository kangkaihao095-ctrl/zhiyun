import { createRouter, createWebHistory } from 'vue-router'
import { authGuard } from './auth-guard'
import Login from './views/Login.vue'
import Home from './views/Home.vue'
import Manuscript from './views/Manuscript.vue'
import Task from './views/Task.vue'
import Billing from './views/Billing.vue'
import History from './views/History.vue'
import Account from './views/Account.vue'
import OpsConsole from './views/OpsConsole.vue'

/** 侧栏四项：论文 / 审校 / 订单 / 设置。观测台 /ops 是独立管理员壳，不进 C 端。 */
export const routes = [
  { path: '/login', component: Login },
  { path: '/ops/login', component: Login, meta: { ops: true } },
  { path: '/', component: Home },
  { path: '/manuscripts/:id', component: Manuscript },
  { path: '/reviews/:id', component: Task },
  { path: '/history', component: History },
  { path: '/ops', component: OpsConsole },
  { path: '/observe', redirect: '/ops' },
  { path: '/chat', redirect: '/' },
  { path: '/models', redirect: { path: '/account', query: { panel: 'models' } } },
  { path: '/billing', component: Billing },
  { path: '/orders/:id', redirect: (to) => ({ path: '/billing', query: { order: String(to.params.id) } }) },
  { path: '/orders', redirect: '/billing' },
  { path: '/account', component: Account }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(authGuard)

export default router
