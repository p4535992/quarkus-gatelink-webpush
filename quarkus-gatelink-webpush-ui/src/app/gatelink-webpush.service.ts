import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { SwPush } from '@angular/service-worker';
import { firstValueFrom } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class GateLinkWebPushService {
  private readonly http = inject(HttpClient);
  private readonly swPush = inject(SwPush);
  private readonly baseUrl = '/api';

  readonly subscription$ = this.swPush.subscription;
  readonly messages$ = this.swPush.messages;
  readonly notificationClicks$ = this.swPush.notificationClicks;

  get isEnabled(): boolean {
    return this.swPush.isEnabled;
  }

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

    const subscription = await this.swPush.requestSubscription({ serverPublicKey });
    await this.registerWithGateLink(subscription);
    return subscription;
  }

  async unsubscribe(): Promise<void> {
    const subscription = await firstValueFrom(this.swPush.subscription);
    if (!subscription) {
      return;
    }

    const encodedEndpoint = this.base64UrlEncode(subscription.endpoint);

    // Keep server and browser state aligned: remove the durable GateLink record
    // first, then unsubscribe from the browser Push Service.
    await firstValueFrom(
      this.http.delete<void>(`${this.baseUrl}/subscriptions/${encodedEndpoint}`),
    );

    await this.swPush.unsubscribe();
  }

  private async registerWithGateLink(subscription: PushSubscription): Promise<void> {
    await firstValueFrom(
      this.http.post<void>(`${this.baseUrl}/subscriptions`, subscription.toJSON()),
    );
  }

  private base64UrlEncode(value: string): string {
    const bytes = new TextEncoder().encode(value);
    const binary = Array.from(bytes, (byte) => String.fromCharCode(byte)).join('');

    return btoa(binary)
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/g, '');
  }
}
