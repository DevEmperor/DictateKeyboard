import type { Limits } from '../config';
import { json } from '../util';

/**
 * `GET /v1/models`
 *
 * Exists because the app uses this endpoint for its connection test and loads the model list
 * when a custom server is added. Without it the test reports "no connection" even though
 * everything works.
 *
 * It returns exactly what the service actually uses — not OpenAI's catalogue. The user should
 * not be able to pick something here that they will not get.
 */
export function handleModels(limits: Limits): Response {
  const now = Math.floor(Date.now() / 1000);
  return json({
    object: 'list',
    data: [
      { id: limits.transcribeModel, object: 'model', created: now, owned_by: 'dictate-cloud' },
      { id: limits.chatModel, object: 'model', created: now, owned_by: 'dictate-cloud' },
    ],
  });
}
