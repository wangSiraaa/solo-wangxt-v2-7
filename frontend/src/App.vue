<template>
  <div style="max-width:1280px;margin:0 auto;padding:20px">
    <div class="row" style="justify-content:space-between;margin-bottom:18px">
      <div>
        <span style="font-size:18px;font-weight:600">🎁 限时道具合成操作台</span>
        <span class="tag info" style="margin-left:10px">规则全部由服务端判定</span>
      </div>
      <div v-if="session.token" class="row">
        <span class="muted">{{ session.user?.displayName }}（{{ roleLabel }}）</span>
        <button class="ghost" @click="doLogout">退出</button>
      </div>
    </div>

    <LoginView v-if="!session.token" @logged-in="onLoggedIn" />
    <PlayerConsole v-else-if="session.role === 'PLAYER'" />
    <OperatorConsole v-else />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { session, logout } from './api'
import LoginView from './views/LoginView.vue'
import PlayerConsole from './views/PlayerConsole.vue'
import OperatorConsole from './views/OperatorConsole.vue'

const roleLabel = computed(() => session.role === 'OPERATOR' ? '运营' : '玩家')
function onLoggedIn() { /* reactive session re-renders */ }
function doLogout() { logout() }
</script>
