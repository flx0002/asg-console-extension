import { useEffect } from 'react';
import { history } from 'ice';

/**
 * /ai-shadow 是配置页的历史入口，配置页已移到 /ai-shadow/config。
 *
 * <p>为什么要挪：侧栏高亮是按 path 前缀匹配菜单项的（getMatchMenu 会把 /ai-shadow
 * 与 /ai-shadow/events 都算作命中 /ai-shadow/events），配页占着 /ai-shadow 就会
 * 出现「点事件、配置也跟着亮」。两个菜单项改成平级子路径后前缀关系消失，只亮一个。
 *
 * <p>这里保留重定向，是为了让已有的书签与外部链接继续可用。
 */
const AiShadowLegacyRedirect = () => {
  useEffect(() => {
    history.replace('/ai-shadow/config');
  }, []);
  return null;
};

export default AiShadowLegacyRedirect;
