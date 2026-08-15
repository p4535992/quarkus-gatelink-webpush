import { Component, inject } from '@angular/core';

import { GateLinkWebPushService } from './gatelink-webpush.service';

@Component({
  selector: 'app-push-page',
  standalone: true,
  template: `
    <section class="card">
      <h2>Browser Web Push</h2>
      <p>
        The browser talks to GateLink through <code>/api</code>. Nginx forwards those
        requests to the Quarkus container; the browser never needs a backend hostname.
      </p>

      <div class="actions">
        <button type="button" [disabled]="busy || !push.isEnabled" (click)="subscribe()">
          Subscribe
        </button>
        <button type="button" [disabled]="busy || !push.isEnabled" (click)="unsubscribe()">
          Unsubscribe
        </button>
      </div>

      <p class="status" [class.error]="error">{{ status }}</p>

      @if (!push.isEnabled) {
        <p class="error">
          Push is not available. Production Web Push requires HTTPS; localhost is allowed
          by browsers for development.
        </p>
      }
    </section>
  `,
})
export class PushPageComponent {
  readonly push = inject(GateLinkWebPushService);

  busy = false;
  error = false;
  status = 'Ready.';

  async subscribe(): Promise<void> {
    await this.run('Subscription registered in GateLink.', () => this.push.subscribe());
  }

  async unsubscribe(): Promise<void> {
    await this.run('Subscription removed from GateLink and browser.', () => this.push.unsubscribe());
  }

  private async run(successMessage: string, action: () => Promise<unknown>): Promise<void> {
    this.busy = true;
    this.error = false;
    this.status = 'Working...';

    try {
      await action();
      this.status = successMessage;
    } catch (cause) {
      this.error = true;
      this.status = cause instanceof Error ? cause.message : 'Unexpected Web Push error.';
    } finally {
      this.busy = false;
    }
  }
}
