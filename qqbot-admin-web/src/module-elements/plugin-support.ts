import { PluginsPage } from '../app/pages/plugins/plugins-page';
import { registerModuleElements } from './register-module-elements';

void registerModuleElements([
  { name: 'qqbot-plugin-support-plugins', component: PluginsPage }
]);
