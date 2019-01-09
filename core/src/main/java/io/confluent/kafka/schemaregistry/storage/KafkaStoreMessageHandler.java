/*
 * Copyright 2014 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.kafka.schemaregistry.storage;

import io.confluent.kafka.schemaregistry.id.IdGenerator;
import io.confluent.kafka.schemaregistry.storage.exceptions.StoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class KafkaStoreMessageHandler
    implements StoreUpdateHandler<SchemaRegistryKey, SchemaRegistryValue> {

  private static final Logger log = LoggerFactory.getLogger(KafkaStoreMessageHandler.class);
  private final KafkaSchemaRegistry schemaRegistry;
  private final LookupCache lookupCache;
  private final ExecutorService tombstoneExecutor;
  private IdGenerator idGenerator;

  public KafkaStoreMessageHandler(KafkaSchemaRegistry schemaRegistry,
                                  LookupCache lookupCache,
                                  IdGenerator idGenerator) {
    this.schemaRegistry = schemaRegistry;
    this.lookupCache = lookupCache;
    this.idGenerator = idGenerator;
    this.tombstoneExecutor = Executors.newSingleThreadExecutor();
  }

  /**
   * Invoked on every new schema written to the Kafka store
   *
   * @param key   Key associated with the schema.
   * @param value Value written to the Kafka lookupCache
   */
  @Override
  public void handleUpdate(SchemaRegistryKey key, SchemaRegistryValue value) {
    if (key.getKeyType() == SchemaRegistryKeyType.SCHEMA) {
      handleSchemaUpdate((SchemaKey) key,
          (SchemaValue) value);
    } else if (key.getKeyType() == SchemaRegistryKeyType.DELETE_SUBJECT) {
      handleDeleteSubject((DeleteSubjectValue) value);
    }
  }

  private void handleDeleteSubject(DeleteSubjectValue deleteSubjectValue) {
    //mark all versions as deleted in the local lookupCache
    String subject = deleteSubjectValue.getSubject();
    Integer deleteTillVersion = deleteSubjectValue.getVersion();
    for (int version = 1; version <= deleteTillVersion; version++) {
      try {

        SchemaKey schemaKey = new SchemaKey(subject, version);
        SchemaValue schemaValue = (SchemaValue) this.lookupCache.get(schemaKey);
        if (schemaValue != null) {
          schemaValue.setDeleted(true);
          lookupCache.put(schemaKey, schemaValue);
          lookupCache.schemaDeleted(schemaKey, schemaValue);
        }
      } catch (StoreException e) {
        log.error("Failed to delete subject in the local Cache");
      }
    }
  }

  private void handleSchemaUpdate(SchemaKey schemaKey, SchemaValue schemaObj) {
    if (schemaObj != null) {
      // If the schema is marked to be deleted, we store it in an internal datastructure
      // that holds all deleted schema keys for an id.
      // Whenever we encounter a new schema for a subject, we check to see if the same schema
      // (same id) was deleted for the subject ever. If so, we tombstone those delete keys.
      // This helps optimize the storage. The main reason we only allow soft deletes in SR is that
      // consumers should be able to access the schemas by id. This is guaranteed when the schema is
      // re-registered again and hence we can tombstone the record.
      if (schemaObj.isDeleted()) {
        this.lookupCache.schemaDeleted(schemaKey, schemaObj);
      } else {
        // Update the maximum id seen so far
        idGenerator.schemaRegistered(schemaKey, schemaObj);
        lookupCache.schemaRegistered(schemaKey, schemaObj);
        List<SchemaKey> schemaKeys = lookupCache.deletedSchemaKeys(schemaObj);
        schemaKeys.stream().filter(v -> v.getSubject().equals(schemaObj.getSubject()))
            .forEach(this::tombstoneSchemaKey);
      }
    }
  }

  private void tombstoneSchemaKey(SchemaKey schemaKey) {
    tombstoneExecutor.execute(() -> {
          try {
            schemaRegistry.getKafkaStore().waitForInit();
            schemaRegistry.getKafkaStore().put(schemaKey, null);
            log.debug("Tombstoned {}", schemaKey);
          } catch (InterruptedException e) {
            log.error("Interrupted while waiting for the tombstone thread to be initialized ", e);
          } catch (StoreException e) {
            log.error("Failed to tombstone {}", schemaKey, e);
          }
        }
    );
  }
}
