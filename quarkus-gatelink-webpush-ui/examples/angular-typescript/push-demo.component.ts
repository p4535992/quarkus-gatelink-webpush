import { Component, inject, signal } from '@angular/core';
import { AsyncPipe, JsonPipe } from '@angular/common';
import { GateLinkWebPushService } from './gatelink-webpush.service';

@Component({
  selector: 'app-push-demo',
  standalone: true,
  imports: [AsyncPipe, JsonPipe],
  template: `
    <h2>GateLink Web Push</h2>

    <p>
      Subscription:
      <code>{{ (push.subscription$ | async)?.endpoint ?? 'not subscribed' }}</code>
    </p>

    <button type="button" (click)="subscribe()" [disabled]="busy()">
      Subscribe
    </button>
    <button type="button" (click)="unsubscribe()" [disabled]="busy()">
      Unsubscribe
    </button>

    @if (error()) {
      <p role="alert">{{ error() }}</p>
    }

    <h3>Last push payload</h3>
    <pre>{{ (push.messages$ | async) | json }}</pre>
  `,
})
export class PushDemoComponent {
  readonly push = inject(GateLinkWebPushService);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  async subscribe(): Promise<void> {
    await this.run(() => this.push.subscribe());
  }

  async unsubscribe(): Promise<void> {
    await this.run(() => this.push.unsubscribe());
  }

  private async run(action: () => Promise<unknown>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : String(error));
    } finally {
      this.busy.set(false);
    }
  }
}
