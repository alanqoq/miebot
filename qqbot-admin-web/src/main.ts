import { bootstrapApplication } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { installModuleBridge } from './app/core/module-bridge';

bootstrapApplication(App, appConfig)
  .then((application) => installModuleBridge(application.injector.get(Router)))
  .catch((err) => console.error(err));
