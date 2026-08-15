import { ApplicationConfig, isDevMode } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideServiceWorker } from '@angular/service-worker';
import { GATELINK_WEBPUSH_BASE_URL } from './gatelink-webpush.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideHttpClient(),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
    {
      provide: GATELINK_WEBPUSH_BASE_URL,
      useValue: 'http://localhost:8080',
    },
  ],
};
