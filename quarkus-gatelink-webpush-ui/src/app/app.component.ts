import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <header>
      <div>
        <p class="eyebrow">Quarkus GateLink</p>
        <h1>Web Push</h1>
      </div>
      <nav aria-label="Main navigation">
        <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Home</a>
        <a routerLink="/dashboard" routerLinkActive="active">Dashboard</a>
        <a routerLink="/settings" routerLinkActive="active">Settings</a>
      </nav>
    </header>

    <main>
      <router-outlet />
    </main>
  `,
})
export class AppComponent {}
