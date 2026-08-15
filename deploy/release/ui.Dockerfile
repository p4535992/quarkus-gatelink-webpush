FROM nginx:1.28.3-alpine

RUN apk add --no-cache openssl \
    && mkdir -p /etc/nginx/tls

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.sh /usr/local/bin/gatelink-entrypoint.sh
COPY dist/ /usr/share/nginx/html/

EXPOSE 80 443
ENTRYPOINT ["/bin/sh", "/usr/local/bin/gatelink-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
