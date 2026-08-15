import { Routes } from '@angular/router';

import { PushPageComponent } from './push-page.component';

export const routes: Routes = [
  { path: '', component: PushPageComponent, title: 'GateLink Web Push' },
  { path: 'dashboard', component: PushPageComponent, title: 'GateLink Dashboard' },
  { path: 'settings', component: PushPageComponent, title: 'GateLink Settings' },
  { path: '**', redirectTo: '' },
];
