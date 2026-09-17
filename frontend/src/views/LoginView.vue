<template>
  <div style="max-width:420px;margin:60px auto">
    <div class="panel">
      <h2>登录</h2>
      <div class="flash error" v-if="error">{{ error }}</div>
      <label class="muted small">账号</label>
      <input v-model="username" placeholder="ops_admin / player1 …" @keyup.enter="doLogin" />
      <div style="height:10px"></div>
      <label class="muted small">密码</label>
      <input v-model="password" type="password" @keyup.enter="doLogin" />
      <div style="height:14px"></div>
      <button style="width:100%" :disabled="loading" @click="doLogin">
        {{ loading ? '登录中…' : '登录' }}
      </button>
    </div>
    <div class="panel small">
      <h3>演示账号（密码均已内置，直接可登录）</h3>
      <div class="row" style="gap:8px">
        <button class="secondary" @click="fill('ops_admin','operator123')">运营 ops_admin</button>
        <button class="secondary" @click="fill('player1','player123')">玩家 player1</button>
        <button class="secondary" @click="fill('player2','player123')">玩家 player2</button>
        <button class="secondary" @click="fill('player3','player123')">玩家 player3</button>
      </div>
      <p class="muted" style="margin:10px 0 0">
        player1 的材料恰好够合成 1 把烈焰之剑，是"最后一份材料并发"的测试锚点。
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { api } from '../api'

const emit = defineEmits(['logged-in'])
const username = ref('player1')
const password = ref('player123')
const error = ref('')
const loading = ref(false)

function fill(u, p) { username.value = u; password.value = p }

async function doLogin() {
  error.value = ''
  loading.value = true
  try {
    await api.login(username.value.trim(), password.value)
    emit('logged-in')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>
