import { post, get, del } from '../../app/control/MicroService.js';
import { isSubscribed } from '../entity/Subscription.js';

const subscribe = async _ => {
    const subscribed = await isSubscribed();
    if (subscribed) {
        console.warn('Already subscribed');
        return;
    }

    const key = await loadPublicKey();
    const registrationOptions = {
        userVisibleOnly: true,
        applicationServerKey: key
    };

    const registration = await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.subscribe(registrationOptions);

    const response = await post('subscriptions', subscription);
    if (!response.ok) {
        console.error('cannot register subscription', response.statusText);
    }
};

const unsubscribe = async _ => {
    const registration = await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.getSubscription();
    if (!subscription) {
        console.warn('not subscribed');
        return;
    }

    const { endpoint } = subscription;
    const encodedEndpoint = base64UrlEncode(endpoint);
    const response = await del(`subscriptions/${encodedEndpoint}`);
    if (!response.ok) {
        console.error('cannot unregister subscription', response.statusText);
        return;
    }

    await subscription.unsubscribe();
    console.info('unsubscribed');
};

const loadPublicKey = async _ => {
    const response = await get('keys/public');
    if (!response.ok) {
        throw new Error(`Cannot load VAPID public key: HTTP ${response.status}`);
    }
    const publicKey = await response.text();
    return base64ToUint8Array(publicKey);
};

const base64UrlEncode = value => btoa(value)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');

const base64ToUint8Array = encodedString => {
    const padded = encodedString.padEnd(Math.ceil(encodedString.length / 4) * 4, '=');
    const decodedString = window.atob(padded);
    return Uint8Array.from(decodedString, char => char.charCodeAt(0));
};

export { subscribe, unsubscribe };
