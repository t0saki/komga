<template>
  <v-container fluid class="pa-6">
    <v-row align="center">
      <v-col cols="12" md="8" lg="6" xl="4">
        <span class="text-capitalize">{{ $t('common.email') }}</span>
        <v-text-field readonly
                      v-model="me.email"
        />
      </v-col>
    </v-row>

    <v-row align="center">
      <v-col>
        <span>{{ $t('common.roles') }}</span>
        <v-chip-group>
          <v-chip v-for="role in me.roles" :key="role"
          >{{ $t(`user_roles.${role}`) }}
          </v-chip>
          <v-chip v-if="me.roles.length === 0">USER</v-chip>
        </v-chip-group>
      </v-col>
    </v-row>

    <v-row>
      <v-col>
        <v-btn color="primary"
               @click.prevent="modalPasswordChange = true"
        >{{ $t('account_settings.change_password') }}
        </v-btn>
      </v-col>
    </v-row>

    <v-row align="center">
      <v-col cols="12" md="8" lg="6" xl="4">
        <span>{{ $t('account_settings.archive_reading') }}</span>
        <v-select
          :items="archiveSizeOptions"
          v-model="archiveMaxMb"
          dense
          filled
          hide-details
        />
        <div class="text-caption text--secondary mt-2">
          {{ $t('account_settings.archive_reading_hint') }}
        </div>
        <div v-if="!$store.getters.meFileDownload" class="text-caption text--secondary mt-1">
          {{ $t('account_settings.archive_reading_requires_file_download') }}
        </div>
      </v-col>
    </v-row>

    <password-change-dialog v-model="modalPasswordChange"
                            :user="me"
    />

  </v-container>
</template>

<script lang="ts">
import PasswordChangeDialog from '@/components/dialogs/PasswordChangeDialog.vue'
import Vue from 'vue'
import {UserDto} from '@/types/komga-users'
import {ARCHIVE_MAX_MB_DEFAULT} from '@/plugins/persisted-state'

export default Vue.extend({
  name: 'AccountSettings',
  components: {PasswordChangeDialog},
  data: () => {
    return {
      modalPasswordChange: false,
      newPassword: '',
    }
  },
  computed: {
    me(): UserDto {
      return this.$store.state.komgaUsers.me
    },
    archiveMaxMb: {
      get(): number {
        return this.$store.state.persistedState.webreader.archiveMaxMb ?? ARCHIVE_MAX_MB_DEFAULT
      },
      set(val: number): void {
        this.$store.commit('setWebreaderArchiveMaxMb', val)
      },
    },
    archiveSizeOptions(): { text: string, value: number }[] {
      return [
        {text: this.$t('account_settings.archive_reading_disabled').toString(), value: 0},
        {text: '100 MB', value: 100},
        {text: '200 MB', value: 200},
        {text: '500 MB', value: 500},
        {text: '1 GB', value: 1000},
      ]
    },
  },
})
</script>

<style scoped>

</style>
