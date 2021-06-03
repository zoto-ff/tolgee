import { ComponentProps, default as React, FunctionComponent } from 'react';
import ListItem from '@material-ui/core/ListItem';
import makeStyles from '@material-ui/core/styles/makeStyles';
import { Box, Theme } from '@material-ui/core';
import createStyles from '@material-ui/core/styles/createStyles';

const useStyles = makeStyles((theme: Theme) =>
  createStyles({
    container: {
      borderBottom: `1px solid ${theme.palette.grey.A100}`,
      '&:last-child': {
        borderBottom: `none`,
      },
    },
  })
);

type PropTypes = Omit<ComponentProps<typeof ListItem>, 'button'> & {
  button?: boolean;
};

export const SimpleListItem: FunctionComponent<PropTypes> = (props) => {
  const classes = useStyles();

  return (
    <ListItem
      data-cy="global-list-item"
      {...props}
      button={props.button as any}
      classes={{ container: classes.container }}
    >
      {props.children}
    </ListItem>
  );
};