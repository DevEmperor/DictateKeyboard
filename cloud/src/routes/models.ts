import { json } from '../util';

/**
 * `GET /v1/models`
 *
 * Exists because the app uses this endpoint for its connection test and loads the model list
 * when a custom server is added. Without it the test reports "no connection" even though
 * everything works.
 *
 * It returns two fixed identifiers of our own rather than whatever the service happens to run
 * behind them. That used to be the real model names, which was harmless while there was one
 * provider and one model each — and stopped being harmless the moment either could change without
 * a deploy. Three reasons, in order of how much they cost:
 *
 *  1. **The app does not use the answer.** It sends `dictate-cloud` as its model and the server
 *     discards that too; this list only has to be non-empty for the connection test to pass.
 *  2. **A name here is a promise.** `@cf/google/gemma-4-26b-a4b-it` in a public response reads as
 *     what Dictate Cloud *is*, and the next model change would then be a broken promise rather
 *     than a configuration edit.
 *  3. **It is somebody else's information.** Which model is behind a paid service is exactly the
 *     kind of internal that costs nothing to keep and cannot be taken back once published.
 */
const MODELS = ['dictate-cloud-transcribe', 'dictate-cloud-reword'] as const;

export function handleModels(): Response {
  const now = Math.floor(Date.now() / 1000);
  return json({
    object: 'list',
    data: MODELS.map((id) => ({ id, object: 'model', created: now, owned_by: 'dictate-cloud' })),
  });
}
