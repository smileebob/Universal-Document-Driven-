// useSchema：页面侧读取实体/单据定义（经 schemaStore 缓存，design/10 §6.2）
import { ref } from 'vue';
import type { Ref } from 'vue';
import type { ObjType, SchemaDef } from '@/modules/config/types';
import { useSchemaStore } from '@/shared/stores/schema';

export function useSchema() {
  const schemaStore = useSchemaStore();
  const schema: Ref<SchemaDef | null> = ref(null);
  const loading = ref(false);
  const notFound = ref(false);

  async function loadSchema(objType: ObjType, code: string, force = false): Promise<SchemaDef | null> {
    loading.value = true;
    notFound.value = false;
    try {
      schema.value = await schemaStore.getSchema(objType, code, force);
      return schema.value;
    } catch {
      notFound.value = true;
      schema.value = null;
      return null;
    } finally {
      loading.value = false;
    }
  }

  return { schema, loading, notFound, loadSchema };
}
