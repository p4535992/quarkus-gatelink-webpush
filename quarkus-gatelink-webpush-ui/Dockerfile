FROM node:24.18.0-bookworm-slim AS build

WORKDIR /workspace

COPY quarkus-gatelink-webpush-ui/package.json ./
RUN npm install --no-audit --no-fund

COPY quarkus-gatelink-webpush-ui/angular.json \
     quarkus-gatelink-webpush-ui/tsconfig.json \
     quarkus-gatelink-webpush-ui/tsconfig.app.json \
     quarkus-gatelink-webpush-ui/ngsw-config.json ./
COPY quarkus-gatelink-webpush-ui/src ./src

RUN npm run build

FROM nginx:1.28.3-alpine

RUN apk add --no-cache openssl \
    && mkdir -p /etc/nginx/tls

COPY config/nginx.conf /etc/nginx/conf.d/default.conf
COPY quarkus-gatelink-webpush-ui/docker-entrypoint.sh /usr/local/bin/gatelink-entrypoint.sh
COPY --from=build /workspace/dist/gatelink-webpush-ui/browser/ /usr/share/nginx/html/

EXPOSE 80 443

ENTRYPOINT ["/bin/sh", "/usr/local/bin/gatelink-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
