FROM node:24.18.0-bookworm-slim AS build

WORKDIR /workspace

COPY package.json ./
RUN npm install --no-audit --no-fund

COPY angular.json tsconfig.json tsconfig.app.json ngsw-config.json ./
COPY src ./src

RUN npm run build

FROM nginx:1.28.3-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/gatelink-webpush-ui/browser/ /usr/share/nginx/html/

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
