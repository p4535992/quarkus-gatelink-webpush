import { inject, Injectable, InjectionToken } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { SwPush } from '@angular/service-worker';
import { firstValueFrom } from 'rxjs';

/**
 * Same-origin default for the production Nginx reverse proxy. Override this
 * token only when integrating GateLink behind a different gateway/path.
 */
export const GATELINK_WEBPUSH_BASE_URL = new InjectionToken<string>(
  'GATELINK_WEBPUSH_BASE_URL',
  { providedIn: 'root', factory: () => '/api' },
);

@Injectable({ providedIn: 'root' })
export class GateLinkWebPushService {
  private readonly http = inject(HttpClient);
  private readonly swPush = inject(SwPush);
  private readonly baseUrl = inject(GATELINK_WEBPUSH_BASE_URL).replace(/\/+$/, '');

  /** Current subscription maintained by Angular's Service Worker integration. */
  readonly subscription$ = this.swPush.subscription;

  /** Push payloads forwarded by Angular's Service Worker. */
  readonly messages$ = this.swPush.messages;

  /** Notification click events forwarded by Angular's Service Worker. */
  readonly notificationClicks$ = this.swPush.notificationClicks;

  async subscribe(): Promise<PushSubscription> {
    if (!this.swPush.isEnabled) {
      throw new Error('Angular Service Worker / Push API is not enabled in this browser.');
    }

    const existing = await firstValueFrom(this.swPush.subscription);
    if (existing) {
      await this.registerWithGateLink(existing);
      return existing;
    }

    const serverPublicKey = (
      await firstValueFrom(
        this.http.get(`${this.baseUrl}/keys/public`, { responseType: 'text' }),
      )
    ).trim();

    const subscription = await this.swPush.requestSubscription({
      serverPublicKey,
    });

    await this.registerWithGateLink(subscription);
    return subscription;
  }

  async unsubscribe(): Promise<void> {
    const subscription = await firstValueFrom(this.swPush.subscription);
    if (!subscription) {
      return;
    }

    const encodedEndpoint = this.base64UrlEncode(subscription.endpoint);

    // Remove the durable server-side record first. If this request fails, keep
    // the browser subscription so the two sides do not silently diverge.
    await firstValueFrom(
      this.http.delete<void>(
        `${this.baseUrl}/subscriptions/${encodedEndpoint}`,
      ),
    );

    await this.swPush.unsubscribe();
  }

  private async registerWithGateLink(subscription: PushSubscription): Promise<void> {
    await firstValueFrom(
      this.http.post<void>(
        `${this.baseUrl}/subscriptions`,
        subscription.toJSON(),
      ),
    );
  }

  private base64UrlEncode(value: string): string {
    const bytes = new TextEncoder().encode(value);
    const binary = Array.from(bytes, byte => String.fromCharCode(byte)).join('');

    return btoa(binary)
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/g, '');
  }
}
