import { default as React, FunctionComponent } from 'react';

import { SadEmotionMessage } from './SadEmotionMessage';
import { Box } from '@material-ui/core';
import { T } from '@tolgee/react';

export const EmptyListMessage: FunctionComponent = (props) => {
  return (
    <Box p={8}>
      <SadEmotionMessage>
        {props.children || <T>global_empty_list_message</T>}
      </SadEmotionMessage>
    </Box>
  );
};