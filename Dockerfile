FROM node:20-alpine

WORKDIR /app
COPY package.json server.js ./
COPY public ./public
RUN mkdir -p /app/data

ENV NODE_ENV=production \
    PORT=3000 \
    DATA_DIR=/app/data \
    TZ=Asia/Shanghai

EXPOSE 3000
VOLUME ["/app/data"]
CMD ["node", "server.js"]
