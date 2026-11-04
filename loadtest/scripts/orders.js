import http from 'k6/http';
import { check } from 'k6';

// Open model: a fixed arrival rate regardless of how slowly the system responds (avoids coordinated omission).
export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 20),
      timeUnit: '1s',
      duration: __ENV.DURATION || '40s',
      preAllocatedVUs: 50,
      maxVUs: 1000,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:create_order}': ['p(95)<500'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const BASE = __ENV.BASE || 'http://localhost:8081';
const SKUS = ['SKU-RED-MUG', 'SKU-BLUE-MUG', 'SKU-TSHIRT-M'];

export default function () {
  const sku = SKUS[__ITER % SKUS.length];
  const body = JSON.stringify({
    customerId: `k6-${__ENV.RUN}-${__VU}`,
    customerEmail: 'k6@example.com',
    currency: 'USD',
    items: [{ sku, quantity: 1, unitPriceMinor: 1000 }],
  });
  const res = http.post(`${BASE}/orders`, body, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `k6-${__ENV.RUN}-${__VU}-${__ITER}` },
    tags: { name: 'create_order' },
  });
  check(res, { 'accepted (202)': (r) => r.status === 202 });
}
