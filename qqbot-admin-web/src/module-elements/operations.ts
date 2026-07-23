import { DashboardPage } from '../app/pages/dashboard/dashboard-page';
import { EventsPage } from '../app/pages/events/events-page';
import { registerModuleElements } from './register-module-elements';

void registerModuleElements([
  { name: 'qqbot-operations-dashboard', component: DashboardPage },
  { name: 'qqbot-operations-events', component: EventsPage }
]);
