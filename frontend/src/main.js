import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// 2026-11-03 修复：Element Plus 默认是**英文** locale。
// 之前没有显式配置，导致内置文案全是英文：删除确认框是 "OK"/"Cancel"、
// 分页器是 "Total 10"、日期选择器是英文月份……而项目自己写的文案全是中文，
// 界面上就成了中英混杂。显式引入中文包才能统一。
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
// 顺序要紧：tokens 必须在 element-plus 的样式之后引入，才能覆盖它的主题变量
import './assets/styles/tokens.css'
import './assets/styles/global.css'

const app = createApp(App)

// 注册 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
