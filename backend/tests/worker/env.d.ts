import type { D1Migration } from '@cloudflare/vitest-pool-workers';
import type { Env as AppEnv } from '../../src/env';

declare global {
  namespace Cloudflare {
    interface Env extends AppEnv {
      CONTROL_MIGRATIONS: D1Migration[];
      ARCHIVE_MIGRATIONS: D1Migration[];
    }
    interface GlobalProps {
      mainModule: typeof import('../../src/index');
    }
  }
}
