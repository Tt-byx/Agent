<script setup>
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

function handleLogout() {
  userStore.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="visitor-layout">
    <el-header class="visitor-header">
      <div class="header-left">
        <el-icon :size="24" color="#5a8a6a"><ChatDotRound /></el-icon>
        <span class="header-title">景区AI导游</span>
      </div>
      <div class="header-right">
        <el-button v-if="userStore.isAdmin()" text @click="router.push('/admin')">管理后台</el-button>
        <template v-if="userStore.user">
          <span class="user-name">{{ userStore.user.nickname || userStore.user.username }}</span>
          <el-button text type="danger" @click="handleLogout">退出</el-button>
        </template>
      </div>
    </el-header>
    <el-main class="visitor-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.visitor-layout {
  height: 100vh;
}

.visitor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
  padding: 0 20px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-name {
  font-size: 13px;
  color: #606266;
}

.visitor-main {
  background: #f5f7fa;
  padding: 0;
  display: flex;
  justify-content: center;
}
</style>
