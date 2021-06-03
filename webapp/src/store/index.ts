import { applyMiddleware, combineReducers, createStore } from 'redux';
import thunkMiddleware from 'redux-thunk';
import { composeWithDevTools } from 'redux-devtools-extension';
import promise from 'redux-promise-middleware';
import { container } from 'tsyringe';
import { ImplicitReducer } from './ImplicitReducer';
import { ProjectActions } from './project/ProjectActions';
import { LanguageActions } from './languages/LanguageActions';
import { GlobalActions } from './global/GlobalActions';
import { ErrorActions } from './global/ErrorActions';
import { RedirectionActions } from './global/RedirectionActions';
import { MessageActions } from './global/MessageActions';
import { SignUpActions } from './global/SignUpActions';
import { ProjectInvitationActions } from './project/invitations/ProjectInvitationActions';
import { SecurityService } from '../service/SecurityService';
import { MessageService } from '../service/MessageService';
import { TranslationActions } from './project/TranslationActions';
import { UserApiKeysActions } from './api_keys/UserApiKeysActions';
import { ExportActions } from './project/ExportActions';
import { UserActions } from './global/UserActions';
import { ScreenshotActions } from './project/ScreenshotActions';
import { OrganizationActions } from './organization/OrganizationActions';
import { ImportActions } from './project/ImportActions';

const implicitReducer = container.resolve(ImplicitReducer);
const projectActions = container.resolve(ProjectActions);
const languageActions = container.resolve(LanguageActions);
const globalActions = container.resolve(GlobalActions);
const errorActions = container.resolve(ErrorActions);
const redirectionActions = container.resolve(RedirectionActions);

const appReducer = (appState, action) =>
  combineReducers({
    translations: implicitReducer.create(
      container.resolve(TranslationActions),
      appState
    ),
    global: implicitReducer.create(globalActions),
    projects: implicitReducer.create(projectActions),
    languages: implicitReducer.create(languageActions),
    error: implicitReducer.create(errorActions),
    redirection: implicitReducer.create(redirectionActions),
    message: implicitReducer.create(container.resolve(MessageActions)),
    signUp: implicitReducer.create(container.resolve(SignUpActions)),
    projectInvitation: implicitReducer.create(
      container.resolve(ProjectInvitationActions)
    ),
    export: implicitReducer.create(container.resolve(ExportActions)),
    userApiKey: implicitReducer.create(container.resolve(UserApiKeysActions)),
    user: implicitReducer.create(container.resolve(UserActions)),
    screenshots: implicitReducer.create(container.resolve(ScreenshotActions)),
    organizations: implicitReducer.create(
      container.resolve(OrganizationActions)
    ),
    import: implicitReducer.create(container.resolve(ImportActions)),
  })(appState, action);

const rootReducer = (state, action): ReturnType<typeof appReducer> => {
  /**
   * reset state on logout
   */
  if (action.type === globalActions.logout.type) {
    state = undefined;
    //remove after login link to avoid buggy behaviour
    container.resolve(SecurityService).setLogoutMark();
  }

  return appReducer(state, action);
};

const successMessageMiddleware = (store) => (next) => (action) => {
  if (
    action.meta &&
    action.meta.successMessage &&
    action.type.indexOf('_PENDING') <= -1 &&
    action.type.indexOf('_REJECTED') <= -1
  ) {
    container.resolve(MessageService).success(action.meta.successMessage);
  }

  next(action);
};

const redirectAfterMiddleware = (store) => (next) => (action) => {
  if (
    action.meta &&
    action.meta.redirectAfter &&
    action.type.indexOf('_PENDING') <= -1 &&
    action.type.indexOf('_REJECTED') <= -1
  ) {
    const path =
      typeof action.meta.redirectAfter === 'function'
        ? action.meta.redirectAfter(action)
        : action.meta.redirectAfter;
    redirectionActions.redirect.dispatch(path);
  }
  next(action);
};

export type AppState = ReturnType<typeof appReducer>;

export default function configureStore() {
  const middlewares = [
    thunkMiddleware,
    promise,
    redirectAfterMiddleware,
    successMessageMiddleware,
  ];
  const middleWareEnhancer = applyMiddleware(...middlewares);

  return createStore(rootReducer, composeWithDevTools(middleWareEnhancer));
}