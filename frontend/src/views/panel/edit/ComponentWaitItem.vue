<template>
  <div class="component-item">
    <mobile-check-bar v-if="mobileCheckBarShow" :element="config" />
    <de-out-widget
      v-if="config.type==='custom'"
      :id="'component' + config.id"
      class="component-custom"
      :style="getComponentStyleDefault(config.style)"
      :out-style="outStyle"
      :element="config"
      :in-screen="true"
    />
    <component
      :is="config.component"
      v-else
      ref="wrapperChild"
      :out-style="outStyle"
      :prop-value="config.propValue"
      :style="getComponentStyleDefault(config.style)"
      :is-edit="false"
      :element="config"
      :h="outItemHeight"
    />
  </div>
</template>

<script>
import { mapState } from 'vuex'
import MobileCheckBar from '@/components/canvas/components/Editor/MobileCheckBar'
import { getStyle } from '@/components/canvas/utils/style'
import DeOutWidget from '@/components/dataease/DeOutWidget'

export default {
  name: 'ComponentWaitItem',
  components: { DeOutWidget, MobileCheckBar },
  props: {
    config: {
      type: Object,
      required: true
    }
  },
  data() {
    return {
      itemWidth: 280,
      itemHeight: 200,
      outStyle: {
        width: this.itemWidth,
        height: this.itemHeight
      }
    }
  },
  computed: {
    outItemHeight() {
      return this.itemHeight - (4 * this.componentGap)
    },
    // 移动端编辑组件选择按钮显示
    mobileCheckBarShow() {
      // 显示条件：1.当前是移动端画布编辑状态
      return this.mobileLayoutStatus
    },
    componentDataWaite() {
      const result = []
      this.componentData.forEach(item => {
        if (!item.mobileSelected) {
          result.push(item)
        }
      })
      return result
    },
    ...mapState([
      'mobileLayoutStatus',
      'componentData',
      'componentGap'
    ])
  },
  methods: {
    getComponentStyleDefault(style) {
      return getStyle(style, ['top', 'left', 'width', 'height', 'rotate'])
    }
  }
}
</script>

<style scoped>
  .component-custom {
    position: relative!important;
    outline: none;
    width: 100% !important;
    height: 100%;
  }
  .component-item {
    padding: 5px;
    height: 200px!important;
  }
</style>
