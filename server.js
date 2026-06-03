import http from 'node:http';

const port = Number(process.env.PORT || 3000);
const appScriptUrl = required('APPS_SCRIPT_WEB_APP_URL');
const webhookSecret = process.env.TELEGRAM_WEBHOOK_SECRET || '';

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/healthz') {
      send(res, 200, 'ok');
      return;
    }

    if (req.method !== 'POST' || req.url !== '/telegram') {
      send(res, 404, 'not found');
      return;
    }

    if (webhookSecret) {
      const actual = req.headers['x-telegram-bot-api-secret-token'];
      if (actual !== webhookSecret) {
        send(res, 401, 'unauthorized');
        return;
      }
    }

    const body = await readBody(req);
    send(res, 200, 'ok');

    forwardToAppsScript(body).catch((error) => {
      console.error('forward failed', error);
    });
  } catch (error) {
    console.error(error);
    send(res, 500, 'error');
  }
});

server.listen(port, () => {
  console.log(`telegram render proxy listening on ${port}`);
});

async function forwardToAppsScript(body) {
  const response = await fetch(appScriptUrl, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body,
    redirect: 'follow'
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`Apps Script returned ${response.status}: ${text.slice(0, 500)}`);
  }
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function send(res, status, text) {
  res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8' });
  res.end(text);
}

function required(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing environment variable: ${name}`);
  }
  return value;
}
