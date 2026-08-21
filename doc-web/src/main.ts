import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './shared/router';
import 'virtual:uno.css';
import './styles/index.css';

createApp(App).use(createPinia()).use(router).mount('#app');
